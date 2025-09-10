package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.type.Chest.Type
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.InventoryHolder

class ChestProtectionListener(
) : Listener {

    private fun isLootChest(block: Block): Boolean {
        val state = block.state
        return when (state) {
            is Chest -> state.lootTable != null
            is Barrel -> state.lootTable != null
            else -> false
        }
    }

    // Prevent the loot table from being unpacked
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLoot(e: LootGenerateEvent) {
        val holder = e.inventoryHolder
        if (holder !is Chest && holder !is Barrel && holder !is DoubleChest) return

        val table = e.lootTable
        e.isCancelled = true

        fun reapply(h: InventoryHolder?) {
            if (h == null) return
            when (h) {
                is DoubleChest -> {
                    reapply(h.leftSide)
                    reapply(h.rightSide)
                }
                is BlockState -> {
                    if (h is org.bukkit.loot.Lootable) {
                        h.lootTable = table
                        h.update(true, false)
                    }
                }
                is org.bukkit.loot.Lootable -> h.lootTable = table
            }
        }
        reapply(holder)
    }

    // Prevent players from breaking loot chests
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isLootChest(block)) return

        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
            event.player.sendActionBar(
                Component.text("You can't break blocks containing loot!")
            )
        } else {
            event.player.sendActionBar(
                Component.text("This chest can now be destroyed.")
            )
        }
    }

    // Prevent loot chests being destroyed by explosions
    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }
    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }

    // Prevent loot chests from merging to double chests
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.block
        if (block.type == Material.CHEST) {
            val state = block.state as? Chest ?: return
            val neighbors = listOf(
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1)
            )
            for (neighbor in neighbors) {
                val neighborState = neighbor.state as? Chest ?: continue
                if (neighborState.lootTable != null) {
                    // Force both to stay single
                    forceSingle(neighborState)
                    forceSingle(state)
                }
            }
        }
    }
    private fun forceSingle(chest: Chest) {
        val data = chest.blockData as? org.bukkit.block.data.type.Chest ?: return
        if (data.type != Type.SINGLE) {
            data.type = Type.SINGLE
            chest.blockData = data
            chest.update(true, false)
        }
    }
}