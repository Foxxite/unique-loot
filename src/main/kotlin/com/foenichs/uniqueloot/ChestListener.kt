package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
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
import org.bukkit.loot.LootTable
import java.util.Random
import java.util.concurrent.CompletableFuture

class ChestListener(private val plugin: UniqueLoot) : Listener {

    // Map: player UUID -> chest ID -> Pair(virtual inventory, original Chest)
    private val playerChests: MutableMap<String, MutableMap<String, Pair<Inventory, Chest>>> = mutableMapOf()

    // Track actual players viewing each chest
    private val chestViewers: MutableMap<String, MutableSet<Player>> = mutableMapOf()

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val block = event.clickedBlock ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block.type != Material.CHEST) return
        val chest = block.state as? Chest ?: return
        val lootTable = chest.lootTable ?: return

        val chestId = "${chest.world.name}_${chest.x}_${chest.y}_${chest.z}"
        val playerUuid = player.uniqueId.toString()

        val chestMap = playerChests.getOrPut(playerUuid) { mutableMapOf() }

        // Create or get virtual inventory paired with the real chest
        val virtualInventoryPair = chestMap.getOrPut(chestId) {
            val inv = org.bukkit.Bukkit.createInventory(player, chest.inventory.size, Component.text("Loot Chest"))

            CompletableFuture.runAsync {
                // Load inventory from SQLite
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

                // Populate loot if empty
                if (inv.all { it == null }) {
                    populateLoot(lootTable, chest, player, inv)
                }
            }

            Pair(inv, chest)
        }

        val virtualInventory = virtualInventoryPair.first
        val realChest = virtualInventoryPair.second

        event.isCancelled = true

        // Handle vanilla-style chest opening
        handleChestOpen(player, realChest, chestId)

        // Open virtual inventory
        player.openInventory(virtualInventory)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val inventory = event.inventory

        val chestMap = playerChests[player.uniqueId.toString()] ?: return
        val chestPair = chestMap.entries.find { it.value.first === inventory }?.value ?: return
        val chestId = chestMap.entries.find { it.value.first === inventory }?.key ?: return
        val realChest = chestPair.second

        // Save inventory asynchronously
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

        // Handle vanilla-style chest closing
        handleChestClose(player, realChest, chestId)
    }

    private fun handleChestOpen(player: Player, chest: Chest, chestId: String) {
        if (player.gameMode == GameMode.SPECTATOR) return

        val viewers = chestViewers.getOrPut(chestId) { mutableSetOf() }
        val wasEmpty = viewers.isEmpty()
        viewers.add(player)
        if (wasEmpty) {
            chest.open() // plays animation for nearby valid players
            chest.world.playSound(chest.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
        }
    }

    private fun handleChestClose(player: Player, chest: Chest, chestId: String) {
        if (player.gameMode == GameMode.SPECTATOR) return

        val viewers = chestViewers[chestId] ?: return
        viewers.remove(player)
        if (viewers.isEmpty()) {
            chestViewers.remove(chestId)
            chest.close() // plays animation for nearby valid players
            chest.world.playSound(chest.location, Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f)
        }
    }

    private fun populateLoot(lootTable: LootTable, chest: Chest, player: Player, inventory: Inventory) {
        val context = LootContext.Builder(chest.location)
            .lootedEntity(player)
            .build()
        val random = Random()
        val items: Collection<ItemStack> = lootTable.populateLoot(random, context)

        // Get all empty slot indices
        val emptySlots = (0 until inventory.size).filter { inventory.getItem(it) == null }.toMutableList()
        emptySlots.shuffle(random)

        // Place items in random empty slots like vanilla
        for (item in items) {
            if (emptySlots.isEmpty()) break
            val slot = emptySlots.removeAt(0)
            inventory.setItem(slot, item)
        }
    }
}
