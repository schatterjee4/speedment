package com.speedment.config.db;

import com.speedment.config.Document;
import com.speedment.config.Document;
import com.speedment.config.db.trait.HasAlias;
import com.speedment.config.db.trait.HasEnabled;
import com.speedment.config.db.trait.HasName;
import com.speedment.config.db.trait.HasParent;
import java.util.Map;
import java.util.stream.Stream;

/**
 *
 * @author Emil Forslund
 */
public interface Schema extends Document, HasParent<Dbms>, HasEnabled, HasName, HasAlias {
    
    final String
        DEFAULT_SCHEMA = "defaultSchema",
        TABLES         = "tables";
    
    /**
     * Returns {@code true} if this schema is the default one, else
     * {@code false}.
     *
     * @return {@code true} if default, else {@code false}
     */
    default boolean isDefaultSchema() {
        return getAsBoolean(DEFAULT_SCHEMA).orElse(false);
    }
    
    default Stream<Table> tables() {
        return children(TABLES, this::newTable);
    }
    
    Table newTable(Map<String, Object> data);
}