package com.foenichs.uniqueloot

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.DriverManager
import java.sql.Connection

class UniqueLoot : JavaPlugin() {

    lateinit var connection: Connection

    override fun onEnable() {
        // Ensure plugin folder exists
        if (!dataFolder.exists()) dataFolder.mkdirs()

        // Create or open SQLite database
        val dbFile = File(dataFolder, "uniqueLoot.db")
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // Create table if it doesn't exist
        connection.prepareStatement("""
            CREATE TABLE IF NOT EXISTS player_chest (
                player_uuid TEXT NOT NULL,
                chest_id TEXT NOT NULL,
                slot INTEGER NOT NULL,
                item_type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                PRIMARY KEY(player_uuid, chest_id, slot)
            );
        """.trimIndent()).use { it.execute() }

        // Register the chest listener
        server.pluginManager.registerEvents(ChestListener(this), this)
    }

    override fun onDisable() {
        // Close database connection on shutdown
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
    }
}
