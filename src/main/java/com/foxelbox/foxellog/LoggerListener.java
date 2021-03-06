/**
 * This file is part of FoxelLog.
 *
 * FoxelLog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FoxelLog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FoxelLog.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foxelbox.foxellog;

import com.foxelbox.foxellog.actions.BaseAction;
import com.foxelbox.foxellog.actions.PlayerBlockAction;
import com.foxelbox.foxellog.actions.PlayerInventoryAction;
import com.foxelbox.foxellog.util.BukkitUtils;
import com.mongodb.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LoggerListener implements Listener {
    private final FoxelLog plugin;
    public LoggerListener(FoxelLog plugin) {
        this.plugin = plugin;
    }

    private Thread queueProcessingThread;
    private volatile boolean enabled = false;

    void enable() {
        enabled = true;
        queueProcessingThread = new Thread() {
            @Override
            public void run() {
                while (enabled) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) { }

                    while (!queuedActions.isEmpty()) {
                        final DB db = plugin.getMongoDB();
                        final DBCollection collection = db.getCollection(BaseAction.getCollection());
                        final BulkWriteOperation bulkWriteOperation = collection.initializeUnorderedBulkOperation();

                        DBObject action;
                        int requestCount = 0;
                        while ((++requestCount < 100) && ((action = queuedActions.poll()) != null))
                            bulkWriteOperation.insert(action);

                        try {
                            bulkWriteOperation.execute();
                        } catch (BulkWriteException e) {
                            for(BulkWriteError error : e.getWriteErrors())
                                queuedActions.add(error.getDetails());
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ex) { }
                            break;
                        }
                    }
                }
            }
        };
        queueProcessingThread.start();
    }

    void disable() {
        enabled = false;
        try {
            if(queueProcessingThread != null)
                queueProcessingThread.join();
        } catch (InterruptedException e) { }
    }

    private final Queue<DBObject> queuedActions = new ConcurrentLinkedQueue<>();
    private void queueAction(BaseAction action) {
        if(!enabled)
            return;
        queuedActions.add(action.toDBObject());
    }

	private void addBlockChange(HumanEntity user, Location location, Material materialBefore, Material materialAfter) {
        queueAction(new PlayerBlockAction(user, location, materialBefore, materialAfter));
	}

	//BLOCK PLAYER EVENTS
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event) {
		addBlockChange(event.getPlayer(), event.getBlockPlaced().getLocation(), event.getBlockReplacedState().getType(), event.getBlockPlaced().getType());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		addBlockChange(event.getPlayer(), event.getBlock().getLocation(), event.getBlock().getType(), Material.AIR);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBucketFill(PlayerBucketFillEvent event) {
		addBlockChange(event.getPlayer(), event.getBlockClicked().getLocation(), event.getBlockClicked().getType(), Material.AIR);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBucketEmpty(PlayerBucketFillEvent event) {
		Material material = event.getBucket();
		switch(event.getBucket()) {
			case WATER_BUCKET:
				material = Material.WATER;
				break;
			case LAVA_BUCKET:
				material = Material.LAVA;
				break;
		}
		addBlockChange(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation(), Material.AIR, material);
	}

	//BASE PLAYER EVENTS
	/*@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerJoin(PlayerJoinEvent event) {
		queueAction(new PlayerAction(event.getPlayer(), "join"));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerQuit(PlayerQuitEvent event) {
		queueAction(new PlayerAction(event.getPlayer(), "quit"));
	}

	@EventHandler(priority = EventPriority.MONITOR) //DO NOT ignoreCancelled = true!!!
	public void onPlayerChat(AsyncPlayerChatEvent event) {
		queueAction(new PlayerChatAction(event.getPlayer(), event.getMessage()));
	}

	@EventHandler(priority = EventPriority.MONITOR) //DO NOT ignoreCancelled = true!!!
	public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
		queueAction(new PlayerChatAction(event.getPlayer(), event.getMessage()));
	}*/

	//INVENTORY PLAYER EVENTS
	private final Map<HumanEntity, ItemStack[]> containers = new HashMap<>();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryClose(InventoryCloseEvent event) {
		final InventoryHolder holder = event.getInventory().getHolder();
		if (holder instanceof BlockState || holder instanceof DoubleChest) {
			final HumanEntity player = event.getPlayer();
			final ItemStack[] before = containers.get(player);
			if (before != null) {
				final ItemStack[] after = BukkitUtils.compressInventory(event.getInventory().getContents());
				final ItemStack[] diff = BukkitUtils.compareInventories(before, after);
				final Location loc = BukkitUtils.getInventoryHolderLocation(holder);
				final Material block = loc.getBlock().getType();
				for (final ItemStack item : diff) {
					queueAction(new PlayerInventoryAction(player, loc, block, item.getType(), item.getAmount()));
				}
				containers.remove(player);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryOpen(InventoryOpenEvent event) {
		if (event.getInventory() != null) {
			final InventoryHolder holder = event.getInventory().getHolder();
			if (holder instanceof BlockState || holder instanceof DoubleChest) {
				if (!BukkitUtils.getInventoryHolderType(holder).equals(Material.WORKBENCH)) {
					containers.put(event.getPlayer(), BukkitUtils.compressInventory(event.getInventory().getContents()));
				}
			}
		}
	}
}
