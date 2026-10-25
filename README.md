# SmarterKeepInventory

SmarterKeepInventory is a Minecraft plugin that allows players to toggle their own keep-inventory status with a twist: enabling it requires the player to stand still for a short period.

## Features

- **Player-Controlled Keep Inventory**: Players can enable or disable their own keep-inventory status.
- **Anti-Cheat Activation**: To enable keep-inventory, players must remain stationary for 5 seconds. Moving or changing worlds cancels the process.
- **Persistent Storage**: Settings are saved across sessions.
- **Combat Balance**: Keep-inventory only triggers if the player dies without a killer, preventing abuse in PvP.
- **Death Notice**: Every death gets one line from the plugin — green when the inventory was kept, red ("You died and lost your inventory.") when it dropped. Trap kills use their own message instead. Text is configurable under `messages.inventory` in `config.yml`.

## Commands

| Command | Description |
| --- | --- |
| `/keepinv` | View your current keep-inventory status. |
| `/keepinv on` | Start the 5-second countdown to enable keep-inventory. |
| `/keepinv off` | Instantly disable keep-inventory. |

## Installation

1. Download the `SmarterKeepInventory-1.1.1.jar`.
2. Place it in your server's `plugins` folder.
3. Restart the server.

## Requirements

- Minecraft Server version 26.1.2 (Paper)

## Metrics

This plugin uses [bStats](https://bstats.org/) (plugin id `34907`) to collect anonymous usage
statistics such as server software, Java version, and which features are enabled. No player names
or UUIDs are collected. You can opt out by setting `metrics-enabled: false` in `config.yml`.
