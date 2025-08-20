package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.LootContext
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.*
import java.util.Base64
import java.util.concurrent.CompletableFuture

class ChestListener(private val plugin: UniqueLoot) : Listener {

    private val playerChests: MutableMap<String, MutableMap<String, Pair<Inventory, Any>>> = mutableMapOf()
    private val chestViewers: MutableMap<String, MutableSet<Player>> = mutableMapOf()

    // Serialization
    @Throws(IOException::class)
    private fun ItemStack.serializeToString(): String {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { out ->
            out.writeObject(this)
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun deserializeItemStack(data: String): ItemStack? {
        val bais = ByteArrayInputStream(Base64.getDecoder().decode(data))
        BukkitObjectInputStream(bais).use { input ->
            return input.readObject() as? ItemStack
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val block = event.clickedBlock ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block.type != Material.CHEST && block.type != Material.BARREL) return

        val blockState = block.state

        // Respect Vanilla interaction rules
        if (player.isSneaking) {
            val handItem = player.inventory.itemInMainHand
            if (handItem.type != Material.AIR) return
        }
        val blockAbove = block.getRelative(0, 1, 0)
        if (blockAbove.type.isSolid) return

        // Only proceed if it has a loot table
        val lootTable = when (blockState) {
            is Chest -> blockState.lootTable
            is Barrel -> blockState.lootTable
            else -> null
        } ?: return

        event.isCancelled = true

        val blockTypeName = if (blockState is Chest) "Chest" else "Barrel"
        val chestId = "${block.world.name}_${block.x}_${block.y}_${block.z}"
        val playerUuid = player.uniqueId.toString()
        val chestMap = playerChests.getOrPut(playerUuid) { mutableMapOf() }

        // Async DB read -> main thread construct & open
        CompletableFuture.supplyAsync {
            // Load saved items (slot -> base64) off-thread
            val saved: MutableList<Pair<Int, String>> = mutableListOf()
            plugin.connection.prepareStatement(
                """
                SELECT slot, item_data FROM player_chest
                WHERE player_uuid = ? AND chest_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, playerUuid)
                stmt.setString(2, chestId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val slot = rs.getInt("slot")
                    val data = rs.getString("item_data") ?: continue
                    saved.add(slot to data)
                }
            }
            saved
        }.whenComplete { savedItems, err ->
            if (err != null) {
                plugin.logger.warning("Failed to load chest items for $playerUuid/$chestId: ${err.message}")
                // still proceed to open with generated loot
            }

            // Back to main thread for all Bukkit object work
            object : BukkitRunnable() {
                override fun run() {
                    // Create inventory
                    val inv = Bukkit.createInventory(
                        player,
                        (blockState as? Chest)?.inventory?.size ?: (blockState as Barrel).inventory.size,
                        Component.text("Loot $blockTypeName")
                    )

                    val hadSaved = !savedItems.isNullOrEmpty()

                    // If no saved items -> generate loot now (main thread)
                    if (!hadSaved) {
                        val context = LootContext.Builder(block.location)
                            .lootedEntity(player)
                            .build()
                        val random = java.util.Random()
                        val items: Collection<ItemStack> = lootTable.populateLoot(random, context)

                        val emptySlots = (0 until inv.size).filter { inv.getItem(it) == null }.toMutableList()
                        emptySlots.shuffle(random)
                        for (item in items) {
                            if (emptySlots.isEmpty()) break
                            inv.setItem(emptySlots.removeAt(0), item)
                        }
                    }

                    // Deserialize saved items on main thread (safe for treasure maps)
                    if (hadSaved) {
                        for ((slot, data) in savedItems) {
                            try {
                                val item = deserializeItemStack(data)
                                if (item != null) inv.setItem(slot, item)
                            } catch (ex: Exception) {
                                plugin.logger.warning("Failed to deserialize item for $playerUuid/$chestId at slot $slot: ${ex.message}")
                            }
                        }
                    }

                    // Track & open
                    chestMap[chestId] = Pair(inv, blockState)
                    handleChestOpen(player, blockState, chestId)
                    player.openInventory(inv)
                }
            }.runTask(plugin)
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val inventory = event.inventory
        val chestMap = playerChests[player.uniqueId.toString()] ?: return
        val entry = chestMap.entries.find { it.value.first === inventory } ?: return
        val chestId = entry.key
        val realBlock = entry.value.second

        // Serialize on main thread (Bukkit objects), then DB write async
        val serializedItems: MutableList<Triple<Int, String, String>> = mutableListOf()
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            try {
                val data = item.serializeToString() // main thread
                serializedItems.add(Triple(slot, data, player.uniqueId.toString()))
            } catch (ex: Exception) {
                plugin.logger.warning("Failed to serialize item for ${player.uniqueId}/$chestId @ $slot: ${ex.message}")
            }
        }

        CompletableFuture.runAsync {
            try {
                // Clear previous
                plugin.connection.prepareStatement(
                    """
                    DELETE FROM player_chest WHERE player_uuid = ? AND chest_id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, player.uniqueId.toString())
                    stmt.setString(2, chestId)
                    stmt.executeUpdate()
                }

                if (serializedItems.isNotEmpty()) {
                    plugin.connection.prepareStatement(
                        """
                        INSERT INTO player_chest (player_uuid, chest_id, slot, item_data)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent()
                    ).use { stmt ->
                        for ((slot, data, uuid) in serializedItems) {
                            stmt.setString(1, uuid)
                            stmt.setString(2, chestId)
                            stmt.setInt(3, slot)
                            stmt.setString(4, data)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("DB write failed for ${player.uniqueId}/$chestId: ${ex.message}")
            }
        }

        handleChestClose(player, realBlock, chestId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()

        playerChests.remove(uuid)
        chestViewers.values.forEach { it.remove(player) }
        chestViewers.entries.removeIf { it.value.isEmpty() }
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