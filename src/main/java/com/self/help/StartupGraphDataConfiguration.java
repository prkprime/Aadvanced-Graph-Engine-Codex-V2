package com.self.help;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.RawDataStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class StartupGraphDataConfiguration {
    private static final List<String> STARTUP_COLUMNS = List.of(
            "fromId",
            "fromLabel",
            "fromType",
            "toId",
            "toLabel",
            "toType",
            "relation",
            "priority");

    private static final List<String[]> STARTUP_ROWS = List.of(
            new String[]{"AUTH", "Authentication Service", "service", "USER_DB", "User Database", "database", "reads", "high"},
            new String[]{"AUTH", "Authentication Service", "service", "TOKEN_CACHE", "Token Cache", "cache", "writes", "high"},
            new String[]{"API", "Public API", "service", "AUTH", "Authentication Service", "service", "calls", "high"},
            new String[]{"API", "Public API", "service", "ORDER", "Order Service", "service", "calls", "high"},
            new String[]{"ORDER", "Order Service", "service", "ORDER_DB", "Order Database", "database", "writes", "high"},
            new String[]{"ORDER", "Order Service", "service", "PAYMENT", "Payment Service", "service", "calls", "high"},
            new String[]{"PAYMENT", "Payment Service", "service", "PAYMENT_DB", "Payment Database", "database", "writes", "high"},
            new String[]{"PAYMENT", "Payment Service", "service", "LEDGER", "Ledger Service", "service", "publishes", "medium"},
            new String[]{"ORDER", "Order Service", "service", "INVENTORY", "Inventory Service", "service", "reserves", "high"},
            new String[]{"INVENTORY", "Inventory Service", "service", "INVENTORY_DB", "Inventory Database", "database", "reads", "high"},
            new String[]{"INVENTORY", "Inventory Service", "service", "SUPPLIER", "Supplier Gateway", "gateway", "syncs", "medium"},
            new String[]{"ORDER", "Order Service", "service", "NOTIFY", "Notification Service", "service", "publishes", "medium"},
            new String[]{"NOTIFY", "Notification Service", "service", "EMAIL", "Email Provider", "provider", "sends", "low"},
            new String[]{"NOTIFY", "Notification Service", "service", "SMS", "Sms Provider", "provider", "sends", "low"},
            new String[]{"API", "Public API", "service", "ANALYTICS", "Analytics Pipeline", "pipeline", "streams", "medium"});

    @Bean
    public RawDataStore graphRawDataStore() {
        return new RawDataStore(STARTUP_COLUMNS);
    }

    @Bean
    public MappingSpec graphMappingSpec() {
        NodeSpec fromNode = new NodeSpec("fromId", "fromLabel", List.of("fromType"));
        NodeSpec toNode = new NodeSpec("toId", "toLabel", List.of("toType"));
        return new MappingSpec(fromNode, toNode, List.of("relation", "priority"));
    }

    @Bean
    public GraphIngestionEngine graphIngestionEngine(RawDataStore graphRawDataStore, MappingSpec graphMappingSpec) {
        return new GraphIngestionEngine(graphRawDataStore, graphMappingSpec);
    }

    @Bean
    public ApplicationRunner startupGraphIngestionRunner(
            RawDataStore graphRawDataStore,
            GraphIngestionEngine graphIngestionEngine) {
        return args -> {
            for (String[] row : STARTUP_ROWS) {
                int rowId = graphRawDataStore.ingestRow(row);
                graphIngestionEngine.ingest(rowId);
            }
        };
    }

    static int startupRowCount() {
        return STARTUP_ROWS.size();
    }
}
