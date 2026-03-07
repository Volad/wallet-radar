package com.walletradar.ingestion.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;

final class ClassifierFixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClassifierFixtureLoader() {
    }

    static RawTransaction loadRawTransaction(String resourcePath) {
        try (InputStream input = ClassifierFixtureLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Fixture not found: " + resourcePath);
            }
            JsonNode root = MAPPER.readTree(input);
            JsonNode rawDataNode = root.path("rawData");
            if (!rawDataNode.isObject()) {
                throw new IllegalArgumentException("Fixture rawData must be an object: " + resourcePath);
            }

            RawTransaction tx = new RawTransaction();
            tx.setTxHash(requireText(root, "txHash", resourcePath));
            tx.setNetworkId(requireText(root, "networkId", resourcePath));
            tx.setWalletAddress(requireText(root, "walletAddress", resourcePath));
            tx.setRawData(Document.parse(MAPPER.writeValueAsString(rawDataNode)));
            return tx;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + resourcePath, e);
        }
    }

    private static String requireText(JsonNode node, String field, String resourcePath) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing field '" + field + "' in fixture: " + resourcePath);
        }
        return value;
    }
}
