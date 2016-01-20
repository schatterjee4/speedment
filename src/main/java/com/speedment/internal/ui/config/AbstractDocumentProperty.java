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
package com.speedment.internal.ui.config;

import com.speedment.exception.SpeedmentException;
import com.speedment.internal.ui.config.trait.HasExpandedProperty;
import com.speedment.util.OptionalBoolean;
import java.util.ArrayList;
import static java.util.Collections.newSetFromMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import static java.util.stream.Collectors.toList;
import java.util.stream.Stream;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import static javafx.collections.FXCollections.observableMap;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import static javafx.collections.FXCollections.observableList;

/**
 *
 * @author Emil Forslund
 */
public abstract class AbstractDocumentProperty implements DocumentProperty, HasExpandedProperty {
    
    /**
     * An observable map containing all the raw data that should be serialized
     * when the config model is saved. The config map can be modified by any
     * thread using the standard {@code Map} operations. It should never be
     * exposed as an {@code ObservableMap} as this would allow users to remove
     * the pre-installed listeners required for this class to work.
     */
    private final ObservableMap<String, Object> config;
    
    /**
     * An internal map of the properties that have been created by this class.
     * Two different properties must never be returned for the same key. A
     * property in this map must be configured to listen for changes in the
     * raw map before being inserted into the map.
     */
    private final transient Map<String, Property<?>> properties;
    
    /**
     * An internal map of the {@link DocumentProperty Document Properties} that 
     * have been created by this class. Once a list is associated with a 
     * particular key, it should never be removed. It should be configured to
     * listen to changes in the raw map before being inserted into this map.
     */
    private final transient ObservableMap<String, ObservableList<DocumentProperty>> documents;
    
    /**
     * An internal flag to indicate if events generated by the observable map
     * {@code config} should be ignored as the change is created internally.
     */
    private final transient EventMonitor monitor;
    
    /**
     * Invalidation listeners required by the {@code Observable} interface.
     */
    private final transient Set<InvalidationListener> listeners;

    /**
     * Wraps the specified raw map in an observable map so that any changes to
     * it can be observed by subclasses of this abstract class. The specified
     * parameter should never be used directly once passed to this constructor
     * as a parameter.
     * 
     * @param data  the raw data map
     */
    protected AbstractDocumentProperty(Map<String, Object> data) {
        this.config     = observableMap(data);
        this.properties = new ConcurrentHashMap<>();
        this.documents  = observableMap(new ConcurrentHashMap<>());
        this.monitor    = new EventMonitor();
        this.listeners  = newSetFromMap(new ConcurrentHashMap<>());
        
        this.config.addListener((MapChangeListener.Change<? extends String, ? extends Object> change) -> {
            
            // Make sure the event was not generated by an internal change to
            // the map.
            if (monitor.isEventsEnabled()) {
                
                // Removal events should not be processed.
                if (change.wasAdded()) {
                    final Object added = change.getValueAdded();
                    
                    // If the added value is a List, it might be a candidate for 
                    // a new child document 
                    if (added instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        final List<Object> addedList = (List<Object>) added;
                        
                        synchronized(documents) {
                            final ObservableList<DocumentProperty> l = documents.get(change.getKey());

                            // If no observable list have been created on
                            // the specified key yet and the proposed list
                            // can de considered a document, create a new
                            // list for it.
                            if (l == null) {
                                final List<DocumentProperty> children = addedList.stream()
                                    .filter(Map.class::isInstance)
                                    .map(DOCUMENT_TYPE::cast)
                                    .map(obj -> createDocument(change.getKey(), obj))
                                    .collect(toList());

                                if (!children.isEmpty()) {
                                    documents.put(change.getKey(), prepareListOnKey(
                                        change.getKey(), 
                                        observableList(new CopyOnWriteArrayList<>(children))
                                    ));
                                }

                            // An observable list already exists on the
                            // specified key. Create document views of the
                            // added elements and insert them into the list.
                            } else {
                                addedList.stream()
                                    .filter(Map.class::isInstance)
                                    .map(DOCUMENT_TYPE::cast)
                                    .map(obj -> createDocument(change.getKey(), obj))
                                    .forEachOrdered(l::add);
                            }
                        }
                        
                    // If it is not a list, it should be considered a property.
                    } else {
                        synchronized (properties) {
                            @SuppressWarnings("unchecked")
                            final Property<Object> p = (Property<Object>) properties.get(change.getKey());

                            // If there is already a property on the specified key, it's
                            // value should be updated to match the new value of the map
                            if (p != null) {
                                p.setValue(added);

                            // There is no property on the specified key since 
                            // before. Create it.
                            } else {
                                final Property<? extends Object> newProperty;
                                
                                if (added instanceof String) {
                                    newProperty = new SimpleStringProperty((String) added);
                                } else if (added instanceof Boolean) {
                                    newProperty = new SimpleBooleanProperty((Boolean) added);
                                } else if (added instanceof Integer) {
                                    newProperty = new SimpleIntegerProperty((Integer) added);
                                } else if (added instanceof Long) {
                                    newProperty = new SimpleLongProperty((Long) added);
                                } else if (added instanceof Double) {
                                    newProperty = new SimpleDoubleProperty((Double) added);
                                } else {
                                    newProperty = new SimpleObjectProperty<>(added);
                                }
                                
                                properties.put(change.getKey(), newProperty);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    public final Map<String, Object> getData() {
        return config;
    }
    
    @Override
    public final Optional<Object> get(String key) {
        return Optional.ofNullable(config.get(key));
    }
    
    @Override
    public final OptionalBoolean getAsBoolean(String key) {
        return OptionalBoolean.ofNullable((Boolean) config.get(key));
    }

    @Override
    public final OptionalLong getAsLong(String key) {
        final Number value = (Number) config.get(key);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    }

    @Override
    public final OptionalDouble getAsDouble(String key) {
        final Number value = (Number) config.get(key);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value.doubleValue());
    }

    @Override
    public final OptionalInt getAsInt(String key) {
        final Number value = (Number) config.get(key);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value.intValue());
    }
    
    @Override
    public final Optional<String> getAsString(String key) {
        return get(key).map(String.class::cast);
    }

    @Override
    public final void put(String key, Object value) {
        config.put(key, value);
    }

    @Override
    public final void addListener(InvalidationListener listener) {
        listeners.add(listener);
    }
    
    @Override
    public final void removeListener(InvalidationListener listener) {
        listeners.remove(listener);
    }
    
    @Override
    public final StringProperty stringPropertyOf(String key, Supplier<String> ifEmpty) {
        return (StringProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleStringProperty(getAsString(k).orElseGet(ifEmpty))));
    }
    
    @Override
    public final IntegerProperty integerPropertyOf(String key, IntSupplier ifEmpty) {
        return (IntegerProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleIntegerProperty(getAsInt(k).orElseGet(ifEmpty))));
    }
    
    @Override
    public final LongProperty longPropertyOf(String key, LongSupplier ifEmpty) {
        return (LongProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleLongProperty(getAsLong(k).orElseGet(ifEmpty))));
    }
    
    @Override
    public final DoubleProperty doublePropertyOf(String key, DoubleSupplier ifEmpty) {
        return (DoubleProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleDoubleProperty(getAsDouble(k).orElseGet(ifEmpty))));
    }
    
    @Override
    public final BooleanProperty booleanPropertyOf(String key, BooleanSupplier ifEmpty) {
        return (BooleanProperty) properties.computeIfAbsent(key, k -> prepare(k, new SimpleBooleanProperty(getAsBoolean(k).orElse(ifEmpty.getAsBoolean()))));
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public final <T> ObjectProperty<T> objectPropertyOf(String key, Class<T> type, Supplier<T> ifEmpty) {
        return (ObjectProperty<T>) properties.computeIfAbsent(key, k -> prepare(k, new SimpleObjectProperty<>(type.cast(get(k).orElseGet(ifEmpty)))));
    }
    
    /**
     * Returns an observable list of all the child documents under a specified
     * key. 
     * <p>
     * The implementation of the document is governed by the 
     * {@link #createDocument(java.lang.String, java.util.Map) createDocument} 
     * method. The specified {@code type} parameter must therefore match the
     * implementation created by 
     * {@link #createDocument(java.lang.String, java.util.Map) createDocument}.
     * 
     * @param <P>          the type of this class
     * @param <T>          the document type
     * @param key          the key to look at
     * @param constructor  the constructor to use to create the children views
     * @return             an observable list of the documents under that key
     * 
     * @throws SpeedmentException  if the specified {@code type} is not the same
     *                             type as {@link #createDocument(java.lang.String, java.util.Map) createDocument}
     *                             generated.
     */
    public final <P extends DocumentProperty, T extends DocumentProperty> ObservableList<T> observableListOf(String key, BiFunction<P, Map<String, Object>, T> constructor) throws SpeedmentException {
        
        // The reason why the results are copied to a separate list before being
        // used in the statement below is to guarantee that only one method is
        // using the monitor the same tame.
        final List<T> existing = new ArrayList<>();
        
        monitor.runWithoutGeneratingEvents(() -> {
            try {
                DOCUMENT_LIST_TYPE.cast(config.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()))
                    .stream().map(child -> constructor.apply((P) this, child))
                    .forEach(existing::add);
            } catch (ClassCastException ex) {
                throw new SpeedmentException(
                    "Requested an ObservableList on key '" + key + 
                    "' of a different type than 'createDocument' created.", ex
                );
            }
        });
        
        try {
            @SuppressWarnings("unchecked")
            final ObservableList<T> list = (ObservableList<T>)
                documents.computeIfAbsent(key, k -> prepareListOnKey(k, observableList(new CopyOnWriteArrayList<>(existing))));

            return list;
        } catch (ClassCastException ex) {
            throw new SpeedmentException(
                "Requested an ObservableList on key '" + key + 
                "' of a different type than 'createDocument' created.", ex
            );
        }
    }

    /**
     * Returns an unmodifiable view of the children to this document, 
     * instantiated using the instantiator specified when the individual
     * documents was first requested.
     * 
     * @return  an unmodifiable observable view of the children
     */
    @Override
    public ObservableMap<String, ObservableList<DocumentProperty>> childrenProperty() {
        return FXCollections.unmodifiableObservableMap(documents);
    }

    protected DocumentProperty createDocument(String key, Map<String, Object> data) {
        return new DefaultDocumentProperty(this, data);
    } 
    
    @SuppressWarnings("unchecked")
    private static final Function<Object, List<Object>> UNCHECKED_LIST_CASTER =
        dp -> (List<Object>)dp;
    
    @Override
    public final Stream<DocumentProperty> children() {
        return stream()
            .filterValue(List.class::isInstance)
            .mapValue(UNCHECKED_LIST_CASTER)
            .flatMapValue(list -> list.stream())
            .filterValue(obj -> obj instanceof Map<?, ?>)
            .mapValue(DOCUMENT_TYPE::cast)
            .mapValue((key, value) -> createDocument(key, value))
            .values();
    }

    @Override
    public final void invalidate() {
        getParent()
            .filter(DocumentProperty.class::isInstance)
            .map(DocumentProperty.class::cast)
            .ifPresent(DocumentProperty::invalidate);
        
        listeners.forEach(listener -> listener.invalidated(this));
    }
    
    private <T, P extends Property<T>> P prepare(String key, P property) {
        property.addListener((ObservableValue<? extends T> observable, T oldValue, T newValue) -> {
            monitor.runWithoutGeneratingEvents(() -> 
                config.put(key, newValue)
            );
            
            invalidate();
        });
        
        return property;
    }
    
    private <T extends DocumentProperty> ObservableList<T> prepareListOnKey(String key, ObservableList<T> list) {

        list.addListener((ListChangeListener.Change<? extends T> change) -> {
            monitor.runWithoutGeneratingEvents(() -> {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> rawList = DOCUMENT_LIST_TYPE.cast(config.get(key));

                while (change.next()) {
                    if (change.wasAdded()) {
                        for (final T added : change.getAddedSubList()) {
                            rawList.add(added.getData());
                        }
                    }

                    if (change.wasRemoved()) {
                        for (final T removed : change.getRemoved()) {
                            rawList.remove(removed.getData());
                        }
                    }
                }
            });
            
            invalidate();
        });

        return list;
    }
    
    private final static class EventMonitor {
        
        private final AtomicBoolean silence = new AtomicBoolean(false);
        
        public boolean isEventsEnabled() {
            return !silence.get();
        }
        
        public void runWithoutGeneratingEvents(Runnable runnable) {
            runWithoutGeneratingEvents(() -> {runnable.run(); return null;});
        }

        public <T> T runWithoutGeneratingEvents(Supplier<T> runnable) {
            final T result;
            synchronized (silence) {
                silence.set(true);
                result = runnable.get();
                silence.set(false);
            }
            
            return result;
        }
    }
}