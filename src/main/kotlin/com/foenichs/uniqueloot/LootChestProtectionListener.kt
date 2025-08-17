package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
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
}