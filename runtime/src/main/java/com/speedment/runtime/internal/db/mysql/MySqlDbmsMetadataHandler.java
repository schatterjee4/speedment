/**
 *
 * Copyright (c) 2006-2016, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.runtime.internal.db.mysql;

import com.speedment.common.injector.annotation.Execute;
import com.speedment.common.injector.annotation.Inject;
import com.speedment.runtime.internal.db.AbstractDbmsMetadataHandler;
import com.speedment.runtime.internal.db.JavaTypeMap;
import java.sql.Blob;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Specific MySQL implementation of a {@link DbmsMetadataHandler}.
 *
 * @author  Per Minborg
 * @author  Emil Forslund
 * @since   2.0.0
 */
public final class MySqlDbmsMetadataHandler extends AbstractDbmsMetadataHandler {
    
    @Execute
    void installCustomTypes(@Inject JavaTypeMap javaTypeMap) {
        javaTypeMap.put("YEAR", Integer.class);
        javaTypeMap.put("JSON", String.class);
        
        Stream.of("LONG", "MEDIUM", "TINY").forEach(key -> {
            javaTypeMap.put(key + "BLOB", Blob.class);
        });
        
        javaTypeMap.addRule((sqlTypeMapping, md) -> {
            // Map a BIT(1) to boolean
            if ("BIT".equalsIgnoreCase(md.getTypeName()) && md.getColumnSize() == 1) {
                return Optional.of(Boolean.class);
            } else return Optional.empty();
        });
    }

}
