# SmarterKeepInventory

SmarterKeepInventory is a Minecraft plugin that allows players to toggle their own keep-inventory status with a twist: enabling it requires the player to stand still for a short period.

## Features

- **Player-Controlled Keep Inventory**: Players can enable or disable their own keep-inventory status.
- **Anti-Cheat Activation**: To enable keep-inventory, players must remain stationary for 5 seconds. Moving or changing worlds cancels the process.
- **Persistent Storage**: Settings are saved across sessions.
- **Combat Balance**: Keep-inventory only triggers if the player dies without a killer, preventing abuse in PvP.

## Commands

| Command | Description |
| --- | --- |
| `/keepinv` | View your current keep-inventory status. |
| `/keepinv on` | Start the 5-second countdown to enable keep-inventory. |
| `/keepinv off` | Instantly disable keep-inventory. |

## Installation

1. Download the `SmarterKeepInventory-1.0.0.jar`.
2. Place it in your server's `plugins` folder.
3. Restart the server.

## Requirements

- Minecraft Server version 1.21
- Spigot/Paper API
