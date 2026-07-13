package com.y271727uy.voxy.common.storage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Set;

public final class StorageConfigCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> GRAPH_FIELDS = Set.of("version", "root");
    private static final Set<String> NODE_FIELDS = Set.of("type", "options", "children");

    private StorageConfigCodec() {
    }

    public static StorageGraphConfig decode(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            JsonObject graph = requireObject(parsed, "storage config");
            rejectUnknownFields(graph, GRAPH_FIELDS, "storage config");
            int version = requireField(graph, "version", "storage config").getAsInt();
            StorageNodeConfig root = decodeNode(requireField(graph, "root", "storage config"), "root");
            return new StorageGraphConfig(version, root);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException exception) {
            throw new JsonParseException("Invalid storage configuration: " + exception.getMessage(), exception);
        }
    }

    public static String encode(StorageGraphConfig config) {
        JsonObject graph = new JsonObject();
        graph.addProperty("version", config.formatVersion());
        graph.add("root", encodeNode(config.root()));
        return GSON.toJson(graph);
    }

    private static StorageNodeConfig decodeNode(JsonElement element, String location) {
        JsonObject node = requireObject(element, location);
        rejectUnknownFields(node, NODE_FIELDS, location);
        String type = requireField(node, "type", location).getAsString();
        JsonObject options = node.has("options")
                ? requireObject(node.get("options"), location + ".options")
                : new JsonObject();
        JsonArray childrenJson = node.has("children")
                ? requireField(node, "children", location).getAsJsonArray()
                : new JsonArray();
        ArrayList<StorageNodeConfig> children = new ArrayList<>(childrenJson.size());
        for (int index = 0; index < childrenJson.size(); index++) {
            children.add(decodeNode(childrenJson.get(index), location + ".children[" + index + "]"));
        }
        return new StorageNodeConfig(type, options, children);
    }

    private static JsonObject encodeNode(StorageNodeConfig config) {
        JsonObject node = new JsonObject();
        node.addProperty("type", config.type());
        node.add("options", config.options());
        JsonArray children = new JsonArray();
        for (StorageNodeConfig child : config.children()) {
            children.add(encodeNode(child));
        }
        node.add("children", children);
        return node;
    }

    private static JsonObject requireObject(JsonElement element, String location) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException(location + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonElement requireField(JsonObject object, String name, String location) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException(location + " is missing required field '" + name + "'");
        }
        return value;
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String location) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new JsonParseException(location + " contains unknown field '" + field + "'");
            }
        }
    }
}
