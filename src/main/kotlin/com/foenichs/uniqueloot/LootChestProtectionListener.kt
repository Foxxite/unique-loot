package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
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

    // Helper to check if a block is a loot chest
    private fun isLootChest(block: Block): Boolean {
        if (block.state !is Chest) return false
        val chest = block.state as Chest
        return chest.lootTable != null
    }

    // Prevent player from breaking loot chests (only allow creative)
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isLootChest(block)) return

        if (event.player.gameMode != GameMode.CREATIVE) {
            event.isCancelled = true
            event.player.sendActionBar(
                Component.text("You can't break loot chests!")
            )
        }
    }

    // Prevent hoppers or other inventory-moving blocks
    @EventHandler
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        val holder = event.source.holder
        if (holder is Chest) {
            val chest = holder
            val chestId = "${chest.world.name}_${chest.x}_${chest.y}_${chest.z}"
            if (chestListener.isLootChest(chestId)) {
                event.isCancelled = true
            }
        }
    }

    // Prevent TNT explosions from destroying loot chests
    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }

    // Prevent entity explosions (e.g., creeper) from destroying loot chests
    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isLootChest(it) }
    }
}
