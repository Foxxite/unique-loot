package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent

class LootChestProtectionListener(
    private val chestListener: ChestListener
) : Listener {

    private fun isLootChest(block: Block): Boolean {
        val state = block.state
        return when (state) {
            is Chest -> state.lootTable != null
            is Barrel -> state.lootTable != null
            else -> false
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isLootChest(block)) return

        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
            event.player.sendActionBar(Component.text("You can't break blocks containing loot!"))
        }
    }

    @EventHandler
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        val holder = event.source.holder
        if (holder is Chest || holder is Barrel) {
            val block = holder as? Block ?: return
            val chestId = "${block.world.name}_${block.x}_${block.y}_${block.z}"
            if (chestListener.isLootChest(chestId)) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }

    // Prevent placing hoppers next to loot containers
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.block.type != Material.HOPPER) return

        val block = event.block
        val directions = listOf(
            block.getRelative(0, 1, 0),  // Above
            block.getRelative(0, -1, 0), // Below
            block.getRelative(1, 0, 0),  // East
            block.getRelative(-1, 0, 0), // West
            block.getRelative(0, 0, 1),  // South
            block.getRelative(0, 0, -1)  // North
        )

        if (directions.any { isLootChest(it) }) {
            event.isCancelled = true
            event.player.sendActionBar(Component.text("You can't interact using hoppers!"))
        }
    }
}