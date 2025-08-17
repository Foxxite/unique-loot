package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.LootContext
import java.util.Random
import java.util.concurrent.CompletableFuture

class ChestListener(private val plugin: UniqueLoot) : Listener {

    private val playerChests: MutableMap<String, MutableMap<String, Pair<Inventory, Any>>> = mutableMapOf()
    private val chestViewers: MutableMap<String, MutableSet<Player>> = mutableMapOf()

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val block = event.clickedBlock ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block.type != Material.CHEST && block.type != Material.BARREL) return

        val blockState = block.state
        val blockTypeName = when (blockState) {
            is Chest -> "Chest"
            is Barrel -> "Barrel"
            else -> return
        }

        val chestId = "${block.world.name}_${block.x}_${block.y}_${block.z}"
        val playerUuid = player.uniqueId.toString()
        val chestMap = playerChests.getOrPut(playerUuid) { mutableMapOf() }

        val virtualInventoryPair = chestMap.getOrPut(chestId) {
            val inv = org.bukkit.Bukkit.createInventory(player, (blockState as? Chest)?.inventory?.size ?: (blockState as Barrel).inventory.size,
                Component.text("Loot $blockTypeName")
            )

            // Populate loot table if available
            val lootTable = when (blockState) {
                is Chest -> blockState.lootTable
                is Barrel -> blockState.lootTable
                else -> null
            }

            if (lootTable != null) {
                val context = LootContext.Builder(block.location)
                    .lootedEntity(player)
                    .build()
                val random = Random()
                val items: Collection<ItemStack> = lootTable.populateLoot(random, context)

                val emptySlots = (0 until inv.size).filter { inv.getItem(it) == null }.toMutableList()
                emptySlots.shuffle(random)
                for (item in items) {
                    if (emptySlots.isEmpty()) break
                    inv.setItem(emptySlots.removeAt(0), item)
                }
            }

            // Load player-specific items asynchronously
            CompletableFuture.runAsync {
                plugin.connection.prepareStatement("""
                    SELECT slot, item_type, amount FROM player_chest
                    WHERE player_uuid = ? AND chest_id = ?
                """).use { stmt ->
                    stmt.setString(1, playerUuid)
                    stmt.setString(2, chestId)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        val slot = rs.getInt("slot")
                        val typeName = rs.getString("item_type") ?: continue
                        val amount = rs.getInt("amount")
                        val material = Material.getMaterial(typeName) ?: continue
                        inv.setItem(slot, ItemStack(material, amount))
                    }
                }
            }

            Pair(inv, blockState)
        }

        val virtualInventory = virtualInventoryPair.first
        val realBlock = virtualInventoryPair.second

        event.isCancelled = true
        handleChestOpen(player, realBlock, chestId)
        player.openInventory(virtualInventory)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val inventory = event.inventory
        val chestMap = playerChests[player.uniqueId.toString()] ?: return
        val chestPairEntry = chestMap.entries.find { it.value.first === inventory } ?: return
        val chestId = chestPairEntry.key
        val realBlock = chestPairEntry.value.second

        CompletableFuture.runAsync {
            plugin.connection.prepareStatement("""
                DELETE FROM player_chest WHERE player_uuid = ? AND chest_id = ?
            """).use { stmt ->
                stmt.setString(1, player.uniqueId.toString())
                stmt.setString(2, chestId)
                stmt.executeUpdate()
            }

            plugin.connection.prepareStatement("""
                INSERT INTO player_chest (player_uuid, chest_id, slot, item_type, amount)
                VALUES (?, ?, ?, ?, ?)
            """).use { stmt ->
                for (slot in 0 until inventory.size) {
                    val item = inventory.getItem(slot) ?: continue
                    stmt.setString(1, player.uniqueId.toString())
                    stmt.setString(2, chestId)
                    stmt.setInt(3, slot)
                    stmt.setString(4, item.type.name)
                    stmt.setInt(5, item.amount)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        handleChestClose(player, realBlock, chestId)
    }

    private fun handleChestOpen(player: Player, blockState: Any, chestId: String) {
        if (player.gameMode == GameMode.SPECTATOR) return

        val viewers = chestViewers.getOrPut(chestId) { mutableSetOf() }
        val wasEmpty = viewers.isEmpty()
        viewers.add(player)
        if (wasEmpty) {
            when (blockState) {
                is Chest -> {
                    blockState.open()
                    blockState.world.playSound(blockState.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
                }
                is Barrel -> {
                    blockState.world.playSound(blockState.location, Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f)
                }
            }
        }
    }

    private fun handleChestClose(player: Player, blockState: Any, chestId: String) {
        if (player.gameMode == GameMode.SPECTATOR) return

        val viewers = chestViewers[chestId] ?: return
        viewers.remove(player)
        if (viewers.isEmpty()) {
            chestViewers.remove(chestId)
            when (blockState) {
                is Chest -> {
                    blockState.close()
                    blockState.world.playSound(blockState.location, Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f)
                }
                is Barrel -> {
                    blockState.world.playSound(blockState.location, Sound.BLOCK_BARREL_CLOSE, 1.0f, 1.0f)
                }
            }
        }
    }

    fun isLootChest(chestId: String): Boolean {
        return playerChests.values.any { it.containsKey(chestId) }
    }
}