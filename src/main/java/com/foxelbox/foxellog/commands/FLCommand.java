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
package com.foxelbox.foxellog.commands;

import com.foxelbox.foxellog.FoxelLog;
import com.foxelbox.foxellog.actions.BaseAction;
import com.foxelbox.foxellog.actions.PlayerBlockAction;
import com.foxelbox.foxellog.actions.PlayerInventoryAction;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.Serializable;
import java.util.*;

public class FLCommand implements CommandExecutor {
    private final FoxelLog plugin;

    public FLCommand(FoxelLog plugin) {
        this.plugin = plugin;
    }

    private class AggregationResult {
        public final String label;
        public int placed = 0;
        public int destroyed = 0;

        private AggregationResult(String label) {
            this(label, 0, 0);
        }

        private AggregationResult(String label, int placed, int destroyed) {
            this.label = label;
            this.placed = placed;
            this.destroyed = destroyed;
        }

        @Override
        public String toString() {
            return "{" + label + "+" + placed + "-" + destroyed + "}";
        }
    }

    enum AggregationMode {
        PLAYERS,
        BLOCKS
    }

    enum PerformMode {
        ROLLBACK,
        REDO,
        GET
    }

    public static class QueryParams implements Serializable {
        BasicDBObject query = new BasicDBObject();
        BasicDBObject sort = new BasicDBObject("date", -1);

        AggregationMode aggregationMode = null;
        PerformMode performMode = PerformMode.GET;

        boolean worldSet = false;
        Location setLocation = null;
        int area = -1;
    }

    private BasicDBObject makeRange(int pos, int range) {
        return new BasicDBObject("$gte", pos - range).append("$lte", pos + range);
    }

    private final HashMap<UUID, QueryParams> lastQueryParams = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String commandName, String[] argsRaw) {
        QueryParams queryParams = new QueryParams();
        UUID myUUID = ((Player) commandSender).getUniqueId();

        for(String arg : argsRaw)
            if(arg.equalsIgnoreCase("last"))
                queryParams = lastQueryParams.get(myUUID);

        queryParams.aggregationMode = null;
        queryParams.performMode = PerformMode.GET;

        lastQueryParams.put(myUUID, queryParams);

        DBCollection collection = plugin.getMongoDB().getCollection(BaseAction.getCollection());

        if(queryParams.setLocation == null)
            queryParams.setLocation = (commandSender instanceof Player) ? ((Player)commandSender).getLocation() : new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0);

        for(int i = 0; i < argsRaw.length; i += 2) {
            String arg = argsRaw[i];
            String param = (i < argsRaw.length - 1) ? argsRaw[i + 1] : "";

            switch(arg.toLowerCase()) {
                case "self":
                case "me":
                case "myself":
                    param = "me";
                    i--;
                case "player":
                    final Set<UUID> playersToMatch = new HashSet<>();
                    for(final String ply : param.split(",")) {
                        if (ply.equals("self") || ply.equals("myself") || ply.equals("me"))
                            playersToMatch.add(((Player) commandSender).getUniqueId());
                        else
                            playersToMatch.add(plugin.getServer().getPlayer(param).getUniqueId());
                    }

                    final int size = playersToMatch.size();
                    if(size == 1)
                        queryParams.query.put("user_uuid", playersToMatch.iterator().next());
                    else if(size > 1)
                        queryParams.query.put("user_uuid", new BasicDBObject("$in", playersToMatch.toArray(new UUID[size])));
                    break;
                case "world":
                    queryParams.worldSet = true;
                    queryParams.setLocation.setWorld(plugin.getServer().getWorld(param));
                    break;
                case "loc":
                case "location":
                    String[] locs = param.split("[,;]+");
                    if(locs.length == 2) {
                        queryParams.setLocation.setX(Integer.parseInt(locs[0]));
                        queryParams.setLocation.setZ(Integer.parseInt(locs[1]));
                    } else if(locs.length == 3) {
                        queryParams.setLocation.setX(Integer.parseInt(locs[0]));
                        queryParams.setLocation.setY(Integer.parseInt(locs[1]));
                        queryParams.setLocation.setZ(Integer.parseInt(locs[2]));
                    }
                    break;
                case "area":
                    queryParams.area = Integer.parseInt(param);
                    break;
                case "since":
                    //All newer than X time
                    break;
                case "before":
                    //All older than X time
                    break;
                case "last":
                    i--; //Ignore!
                    break;
                case "rollback":
                    queryParams.performMode = PerformMode.ROLLBACK;
                    i--;
                    break;
                case "redo":
                    queryParams.performMode = PerformMode.REDO;
                    i--;
                    break;
                case "sum":
                    switch(param.toLowerCase()) {
                        case "player":
                        case "players":
                            queryParams.aggregationMode = AggregationMode.PLAYERS;
                            break;
                        case "block":
                        case "blocks":
                            queryParams.aggregationMode = AggregationMode.BLOCKS;
                            break;
                    }
                    break;
            }
        }

        if(queryParams.performMode != PerformMode.GET && queryParams.aggregationMode != null) {
            commandSender.sendMessage("You can only use the display/default mode while aggregation/sum is turned on!");
            return false;
        }

        if(queryParams.area >= 0) {
            queryParams.query.put("location",
                    new BasicDBObject("world", queryParams.setLocation.getWorld().getName())
                        .append("x", makeRange(queryParams.setLocation.getBlockX(), queryParams.area))
                        .append("y", makeRange(queryParams.setLocation.getBlockY(), queryParams.area))
                        .append("z", makeRange(queryParams.setLocation.getBlockZ(), queryParams.area))
            );
        } else if(queryParams.worldSet) {
            queryParams.query.put("location", new BasicDBObject("world", queryParams.setLocation.getWorld().getName()));
        }

        final long timeStart = System.nanoTime();

        if(queryParams.aggregationMode != null) {
            queryParams.query.put("type", "player_block_change");

            ArrayList<DBObject> aggregationPipeline = new ArrayList<>();

            aggregationPipeline.add(new BasicDBObject("$match", queryParams.query));

            BasicDBObject project = new BasicDBObject("_id", 0);
            project.put("blockFrom", 1);
            project.put("blockTo", 1);
            aggregationPipeline.add(new BasicDBObject("$project", project));
            BasicDBObject groups = new BasicDBObject();
            aggregationPipeline.add(new BasicDBObject("$group", groups));

            Collection<AggregationResult> results = null;
            String label = null;

            switch(queryParams.aggregationMode) {
                case PLAYERS:
                    label = "Player";
                    project.put("user_uuid", 1);

                    groups.append("_id", "$user_uuid");

                    groups.append("placed", new BasicDBObject("$sum", new BasicDBObject("$cond", Arrays.asList(new BasicDBObject("$eq", Arrays.asList("$blockTo", null)), 0, 1))));
                    groups.append("destroyed", new BasicDBObject("$sum", new BasicDBObject("$cond", Arrays.asList(new BasicDBObject("$eq", Arrays.asList("$blockFrom", null)), 0, 1))));

                    results = new ArrayList<>();
                    for(DBObject res : collection.aggregate(aggregationPipeline).results()) {
                        results.add(new AggregationResult(plugin.getServer().getOfflinePlayer((UUID)res.get("_id")).getName(), (int)res.get("placed"), (int)res.get("destroyed")));
                    }
                    break;
                case BLOCKS:
                    label = "Block";

                    Map<String, AggregationResult> resultMap = new HashMap<>();

                    queryParams.query.put("blockFrom", new BasicDBObject("$ne", null));
                    groups.append("_id", "$blockFrom");
                    groups.append("value", new BasicDBObject("$sum", 1));
                    for(DBObject res : collection.aggregate(aggregationPipeline).results()) {
                        System.out.println(res.toMap());
                        String key = (String)res.get("_id");
                        AggregationResult result = resultMap.get(key);
                        if(result == null) {
                            result = new AggregationResult(key);
                            resultMap.put(key, result);
                        }
                        result.destroyed = (int)res.get("value");
                     }

                    queryParams.query.remove("blockFrom");
                    queryParams.query.put("blockTo", new BasicDBObject("$ne", null));
                    groups.put("_id", "$blockTo");
                    groups.put("value", new BasicDBObject("$sum", 1));
                    for(DBObject res : collection.aggregate(aggregationPipeline).results()) {
                        String key = (String)res.get("_id");
                        AggregationResult result = resultMap.get(key);
                        if(result == null) {
                            result = new AggregationResult(key);
                            resultMap.put(key, result);
                        }
                        result.placed = (int)res.get("value");
                    }

                    results = resultMap.values();
                    break;
            }

            System.out.println(results);
        } else {
            switch (queryParams.performMode) {
                case GET:
                    queryParams.query.put("state", 0);
                    DBCursor getCursor = collection.find(queryParams.query).sort(queryParams.sort);

                    List<BaseAction> getActions = new ArrayList<>();

                    for(DBObject dbObject : getCursor)
                        getActions.add(BaseAction.craftActionByTypeAndDBObject(dbObject));

                    System.out.println(getActions);
                    break;
                case ROLLBACK:
                    queryParams.query.put("state", 0);
                    DBCursor cursor = collection.find(queryParams.query).sort(new BasicDBObject("date", -1));

                    List<PlayerBlockAction> blockActions = new ArrayList<>();
                    List<PlayerInventoryAction> inventoryActions = new ArrayList<>();

                    for(DBObject dbObject : cursor) {
                        BaseAction action = BaseAction.craftActionByTypeAndDBObject(dbObject);
                        if(action instanceof PlayerBlockAction)
                            blockActions.add((PlayerBlockAction)action);
                        else if(action instanceof PlayerInventoryAction)
                            inventoryActions.add((PlayerInventoryAction)action);
                    }

                    Map<Location, Material> setMaterials = new HashMap<>();

                    for(PlayerBlockAction action : blockActions) {
                        Material currentMaterial;
                        Location currentLocation = action.getLocation();
                        if (!setMaterials.containsKey(currentLocation))
                            setMaterials.put(currentLocation, currentLocation.getBlock().getType());
                        currentMaterial = setMaterials.get(currentLocation);

                        if (currentMaterial.equals(action.getBlockTo())) {
                            action.state = 1;
                            collection.update(new BasicDBObject("_id", action.getDbID()), action.toDBObject());
                            setMaterials.put(currentLocation, action.getBlockFrom());
                        }
                    }

                    for(Map.Entry<Location, Material> setMaterial : setMaterials.entrySet()) {
                        setMaterial.getKey().getBlock().setType(setMaterial.getValue());
                    }
                    break;
                case REDO:
                    queryParams.query.put("state", 1);
                    DBCursor cursor2 = collection.find(queryParams.query).sort(new BasicDBObject("date", 1));

                    List<PlayerBlockAction> blockActions2 = new ArrayList<>();
                    List<PlayerInventoryAction> inventoryActions2 = new ArrayList<>();

                    for(DBObject dbObject : cursor2) {
                        BaseAction action = BaseAction.craftActionByTypeAndDBObject(dbObject);
                        if(action instanceof PlayerBlockAction)
                            blockActions2.add((PlayerBlockAction)action);
                        else if(action instanceof PlayerInventoryAction)
                            inventoryActions2.add((PlayerInventoryAction)action);
                    }

                    Map<Location, Material> setMaterials2 = new HashMap<>();

                    for(PlayerBlockAction action : blockActions2) {
                        Material currentMaterial;
                        Location currentLocation = action.getLocation();
                        if (!setMaterials2.containsKey(currentLocation))
                            setMaterials2.put(currentLocation, currentLocation.getBlock().getType());
                        currentMaterial = setMaterials2.get(currentLocation);

                        if (currentMaterial.equals(action.getBlockFrom())) {
                            action.state = 0;
                            collection.update(new BasicDBObject("_id", action.getDbID()), action.toDBObject());
                            setMaterials2.put(currentLocation, action.getBlockTo());
                        }
                    }

                    for(Map.Entry<Location, Material> setMaterial : setMaterials2.entrySet()) {
                        setMaterial.getKey().getBlock().setType(setMaterial.getValue());
                    }
                    break;
            }
        }

        final long timeEnd = System.nanoTime();

        commandSender.sendMessage("Time taken: " + (((double) (timeEnd - timeStart)) / 1000000000D) + " seconds");

        return true;
    }
}
