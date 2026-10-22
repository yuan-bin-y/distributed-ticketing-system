package com.byy.ticket.event.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventDatabaseBudgetTest {
    @Test void scalingRequiresSharingMeasuredBudget() {
        var metrics=new SimpleMeterRegistry();try {
            assertThrows(IllegalArgumentException.class,()->new EventDatabaseBudget(metrics,16,3,16,20,4));
            new EventDatabaseBudget(metrics,8,2,16,20,4);
            assertEquals(16,metrics.get("ticket.event.database.planned.loads").gauge().value());
            assertEquals(40,metrics.get("ticket.event.database.planned.connections").gauge().value());
        }finally{metrics.close();}
    }
    @Test void reservesConnectionsForWritesAndRecovery() {
        var metrics=new SimpleMeterRegistry();try {
            assertThrows(IllegalArgumentException.class,()->new EventDatabaseBudget(metrics,16,1,16,16,4));
            assertThrows(IllegalArgumentException.class,()->new EventDatabaseBudget(metrics,16,0,16,20,4));
        }finally{metrics.close();}
    }
}
