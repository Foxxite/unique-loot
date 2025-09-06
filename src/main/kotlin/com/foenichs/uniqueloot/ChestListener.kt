package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.type.Chest.Type
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
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture

class ChestListener(private val plugin: UniqueLoot) : Listener {

    private val playerInventories: MutableMap<UUID, MutableMap<String, Opened>> = mutableMapOf()
    private val viewers: MutableMap<String, MutableSet<UUID>> = mutableMapOf()

    private data class Opened(val inv: Inventory, val anchorBlock: Block, val type: ContainerKind)
    private enum class ContainerKind { SINGLE_CHEST, DOUBLE_CHEST, BARREL }

    // Serialization

    @Throws(IOException::class)
    private fun ItemStack.serializeToString(): String {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { out -> out.writeObject(this) }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun deserializeItemStack(data: String): ItemStack? {
        val bytes = Base64.getDecoder().decode(data)
        ByteArrayInputStream(bytes).use { bais ->
            BukkitObjectInputStream(bais).use { inStream -> return inStream.readObject() as? ItemStack }
        }
    }

    // Canonical IDs

    private fun canonicalId(block: Block): String {
        val state = block.state
        return when (state) {
            is Chest -> canonicalChestId(state)
            is Barrel -> "${block.world.uid}:${block.x},${block.y},${block.z}"
            else -> "${block.world.uid}:${block.x},${block.y},${block.z}"
        }
    }

    private fun canonicalChestId(chest: Chest): String {
        val holder = chest.inventory.holder
        if (holder is DoubleChest) {
            val left = holder.leftSide as? Chest
            val right = holder.rightSide as? Chest
            if (left != null && right != null) {
                val minX = minOf(left.x, right.x)
                val minY = minOf(left.y, right.y)
                val minZ = minOf(left.z, right.z)
                return "${left.world.uid}:${minX},${minY},${minZ}"
            }
        }
        return "${chest.world.uid}:${chest.x},${chest.y},${chest.z}"
    }

    private fun isBlockedAbove(block: Block): Boolean {
        val above = block.world.getBlockAt(block.x, block.y + 1, block.z)
        return above.type.isOccluding
    }

    // Events

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        val block = e.clickedBlock ?: return
        val state = block.state

        val isChest = state is Chest
        val isBarrel = state is Barrel
        if (!isChest && !isBarrel) return

        val player = e.player
        if (player.isSneaking) return
        if (isBlockedAbove(block)) return

        val lootTable = when (state) {
            is Chest -> state.lootTable
            is Barrel -> state.lootTable
            else -> null
        } ?: return

        e.isCancelled = true

        val containerId = if (isChest) canonicalChestId(state) else canonicalId(block)
        val kind = when {
            isChest && state.blockData is org.bukkit.block.data.type.Chest && (state.blockData as org.bukkit.block.data.type.Chest).type != Type.SINGLE -> ContainerKind.DOUBLE_CHEST
            isChest -> ContainerKind.SINGLE_CHEST
            else -> ContainerKind.BARREL
        }

        val perPlayer = playerInventories.getOrPut(player.uniqueId) { mutableMapOf() }
        perPlayer[containerId]?.let { opened ->
            openFor(player, containerId, opened)
            return
        }

        CompletableFuture.supplyAsync { loadFromDb(player.uniqueId, containerId) }.whenComplete { saved, err ->
            if (err != null) plugin.logger.warning("" +
                    "DB load failed for ${player.uniqueId}/$containerId: ${err.message}")

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val size = when (kind) {
                    ContainerKind.BARREL, ContainerKind.SINGLE_CHEST -> 27
                    ContainerKind.DOUBLE_CHEST -> 54
                }
                val title = when (kind) {
                    ContainerKind.BARREL -> Component.text("Loot ").append(Component.translatable("container.barrel"))
                    ContainerKind.SINGLE_CHEST, ContainerKind.DOUBLE_CHEST -> Component.text("Loot ").append(Component.translatable("container.chest"))
                }

                val inv = Bukkit.createInventory(player, size, title)

                if (saved != null) {
                    for ((slot, data) in saved) {
                        try {
                            deserializeItemStack(data)?.let { inv.setItem(slot, it) }
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    val rng = Random()
                    val context = LootContext.Builder(block.location).lootedEntity(player).build()
                    val items = mutableListOf<ItemStack>()
                    if (isChest) {
                        val holder = state.inventory.holder
                        if (holder is DoubleChest) {
                            (holder.leftSide as? Chest)?.lootTable?.populateLoot(rng, context)?.let { items.addAll(it) }
                            (holder.rightSide as? Chest)?.lootTable?.populateLoot(rng, context)
                                ?.let { items.addAll(it) }
                        } else {
                            lootTable.populateLoot(rng, context).let { items.addAll(it) }
                        }
                    } else {
                        lootTable.populateLoot(rng, context).let { items.addAll(it) }
                    }

                    val emptySlots = (0 until inv.size).filter { inv.getItem(it) == null }.toMutableList()
                    emptySlots.shuffle(rng)
                    for (it in items) {
                        if (emptySlots.isEmpty()) break
                        inv.setItem(emptySlots.removeAt(0), it)
                    }
                }

                val opened = Opened(inv, block, kind)
                perPlayer[containerId] = opened
                openFor(player, containerId, opened)
            })
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val perPlayer = playerInventories[player.uniqueId] ?: return
        val entry = perPlayer.entries.find { it.value.inv === e.inventory } ?: return

        val containerId = entry.key
        val opened = entry.value

        val toStore = buildList {
            for (slot in 0 until opened.inv.size) {
                opened.inv.getItem(slot)?.let { add(slot to it.serializeToString()) }
            }
        }

        CompletableFuture.runAsync { saveToDb(player.uniqueId, containerId, toStore) }

        closeFor(player, containerId, opened)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val uuid = e.player.uniqueId
        playerInventories.remove(uuid)
        viewers.values.forEach { it.remove(uuid) }
        viewers.entries.removeIf { it.value.isEmpty() }
    }

    // Open / Close

    private fun openFor(player: Player, containerId: String, opened: Opened) {
        if (player.gameMode == GameMode.SPECTATOR) return
        val set = viewers.getOrPut(containerId) { mutableSetOf() }
        val wasEmpty = set.isEmpty()
        set.add(player.uniqueId)
        if (wasEmpty) playOpenAnimation(opened)
        player.openInventory(opened.inv)
    }

    private fun closeFor(player: Player, containerId: String, opened: Opened) {
        if (player.gameMode == GameMode.SPECTATOR) return
        val set = viewers[containerId] ?: return
        set.remove(player.uniqueId)
        if (set.isEmpty()) {
            viewers.remove(containerId)
            playCloseAnimation(opened)
        }
    }

    private fun playOpenAnimation(opened: Opened) {
        val loc = getChestAnimationLocation(opened)
        when (opened.type) {
            ContainerKind.BARREL -> opened.anchorBlock.world.playSound(loc, Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f)
            else -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Chest) state.open()
            }
        }
    }

    private fun playCloseAnimation(opened: Opened) {
        val loc = getChestAnimationLocation(opened)
        when (opened.type) {
            ContainerKind.BARREL -> opened.anchorBlock.world.playSound(loc, Sound.BLOCK_BARREL_CLOSE, 1.0f, 1.0f)
            else -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Chest) state.close()
            }
        }
    }

    private fun getChestAnimationLocation(opened: Opened): org.bukkit.Location {
        val block = opened.anchorBlock
        if (opened.type != ContainerKind.DOUBLE_CHEST) return block.location.add(0.5, 0.5, 0.5)
        val state = block.state as? Chest ?: return block.location.add(0.5, 0.5, 0.5)
        val holder = state.inventory.holder as? DoubleChest ?: return block.location.add(0.5, 0.5, 0.5)
        val left = holder.leftSide as? Chest
        val right = holder.rightSide as? Chest
        if (left != null && right != null) {
            val midX = (left.x + right.x) / 2.0 + 0.5
            val midY = (left.y + right.y) / 2.0 + 0.5
            val midZ = (left.z + right.z) / 2.0 + 0.5
            return org.bukkit.Location(left.world, midX, midY, midZ)
        }
        return block.location.add(0.5, 0.5, 0.5)
    }

    // DB Persistence

    private fun loadFromDb(playerId: UUID, containerId: String): List<Pair<Int, String>>? {
        try {
            plugin.connection.prepareStatement(
                "SELECT slot, item_data FROM player_chest WHERE player_uuid = ? AND chest_id = ? ORDER BY slot ASC"
            ).use { stmt ->
                stmt.setString(1, playerId.toString())
                stmt.setString(2, containerId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Pair<Int, String>>()
                    while (rs.next()) {
                        val slot = rs.getInt("slot")
                        val data = rs.getString("item_data") ?: continue
                        out.add(slot to data)
                    }
                    return if (out.isEmpty()) null else out
                }
            }
        } catch (t: Throwable) {
            plugin.logger.warning("loadFromDb error: ${t.message}")
            return null
        }
    }

    private fun saveToDb(playerId: UUID, containerId: String, items: List<Pair<Int, String>>) {
        try {
            plugin.connection.autoCommit = false
            plugin.connection.prepareStatement(
                "DELETE FROM player_chest WHERE player_uuid = ? AND chest_id = ?"
            ).use { del ->
                del.setString(1, playerId.toString())
                del.setString(2, containerId)
                del.executeUpdate()
            }
            if (items.isNotEmpty()) {
                plugin.connection.prepareStatement(
                    "INSERT INTO player_chest (player_uuid, chest_id, slot, item_data) VALUES (?, ?, ?, ?)"
                ).use { ins ->
                    for ((slot, data) in items) {
                        ins.setString(1, playerId.toString())
                        ins.setString(2, containerId)
                        ins.setInt(3, slot)
                        ins.setString(4, data)
                        ins.addBatch()
                    }
                    ins.executeBatch()
                }
            }
            plugin.connection.commit()
        } catch (t: Throwable) {
            plugin.logger.warning("saveToDb error: ${t.message}")
            try {
                plugin.connection.rollback()
            } catch (_: Throwable) {
            }
        } finally {
            try {
                plugin.connection.autoCommit = true
            } catch (_: Throwable) {
            }
        }
    }
}