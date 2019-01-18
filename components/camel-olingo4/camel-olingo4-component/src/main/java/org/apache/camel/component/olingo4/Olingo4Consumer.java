/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.olingo4;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.component.olingo4.api.Olingo4ResponseHandler;
import org.apache.camel.component.olingo4.internal.Olingo4ApiName;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.component.AbstractApiConsumer;
import org.apache.camel.util.component.ApiConsumerHelper;
import org.apache.olingo.client.api.domain.ClientEntity;
import org.apache.olingo.client.api.domain.ClientEntitySet;

/**
 * The Olingo4 consumer.
 */
public class Olingo4Consumer extends AbstractApiConsumer<Olingo4ApiName, Olingo4Configuration> {

    private Set<Integer> resultIndex = null;

    public Olingo4Consumer(Olingo4Endpoint endpoint, Processor processor) {
        super(endpoint, processor);
    }

    @Override
    protected int poll() throws Exception {
        // invoke the consumer method
        final Map<String, Object> args = new HashMap<String, Object>();
        args.putAll(endpoint.getEndpointProperties());

        // let the endpoint and the Consumer intercept properties
        endpoint.interceptProperties(args);
        interceptProperties(args);

        try {
            // create responseHandler
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] result = new Object[1];
            final Exception[] error = new Exception[1];

            args.put(Olingo4Endpoint.RESPONSE_HANDLER_PROPERTY, new Olingo4ResponseHandler<Object>() {
                @Override
                public void onResponse(Object response, Map<String, String> responseHeaders) {
                    if (resultIndex != null) {
                        response = filterResponse(response);
                    }

                    result[0] = response;
                    latch.countDown();
                }

                @Override
                public void onException(Exception ex) {
                    error[0] = ex;
                    latch.countDown();
                }

                @Override
                public void onCanceled() {
                    error[0] = new RuntimeCamelException("OData HTTP Request cancelled");
                    latch.countDown();
                }
            });

            doInvokeMethod(args);

            // guaranteed to return, since an exception on timeout is
            // expected!!!
            latch.await();

            if (error[0] != null) {
                throw error[0];
            }

            //
            // Allow consumer idle properties to properly handle an empty polling response
            //
            int processed = ApiConsumerHelper.getResultsProcessed(this, result[0], isSplitResult());
            if (result[0] instanceof ClientEntitySet && (((ClientEntitySet) result[0]).getEntities().isEmpty())) {
                return 0;
            } else {
                return processed;
            }

        } catch (Throwable t) {
            throw ObjectHelper.wrapRuntimeCamelException(t);
        }
    }

    private Object filter(Object o) {
        if (resultIndex.contains(o.hashCode())) {
            return null;
        }
        return o;
    }

    private void index(Object o) {
        resultIndex.add(o.hashCode());
    }

    private Iterable<?> filter(Iterable<?> iterable) {
        List<Object> filtered = new ArrayList<>();
        for (Object o : iterable) {
            if (resultIndex.contains(o.hashCode())) {
                continue;
            }
            filtered.add(o);
        }

        return filtered;
    }

    private void index(Iterable<?> iterable) {
        for (Object o : iterable) {
            resultIndex.add(o.hashCode());
        }
    }

    private ClientEntitySet filter(ClientEntitySet entitySet) {
        List<ClientEntity> entities = entitySet.getEntities();

        if (entities.isEmpty()) {
            return entitySet;
        }

        List<ClientEntity> copyEntities = new ArrayList<>();
        copyEntities.addAll(entities);

        for (ClientEntity entity : copyEntities) {
            if (resultIndex.contains(entity.hashCode())) {
                entities.remove(entity);
            }
        }

        return entitySet;
    }

    private void index(ClientEntitySet entitySet) {
        for (ClientEntity entity : entitySet.getEntities()) {
            resultIndex.add(entity.hashCode());
        }
    }

    @SuppressWarnings( "unchecked" )
    protected Object filterResponse(Object response) {
        if (response instanceof ClientEntitySet) {
            response = filter((ClientEntitySet) response);
        } else if (response instanceof Iterable) {
            response = filter((Iterable<Object>) response);
        } else if (response.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            final int size = Array.getLength(response);
            for (int i = 0; i < size; i++) {
                result.add(Array.get(response, i));
            }
            response = filter(result);
        } else {
            response = filter(response);
        }

        return response;
    }

    @Override
    public void interceptProperties(Map<String, Object> properties) {
        //
        // If we have a filterAlreadySeen property then initialise the filter index
        //
        Object value = properties.get(Olingo4Endpoint.FILTER_ALREADY_SEEN);
        if (value == null) {
            return;
        }

        //
        // Initialise the index if not already and if filterAlreadySeen has been set
        //
        if (Boolean.parseBoolean(value.toString()) && resultIndex == null) {
            resultIndex = new HashSet<>();
        }
    }

    @Override
    public void interceptResult(Object result, Exchange resultExchange) {
        if (resultIndex == null) {
            return;
        }

        //
        // Index the results
        //
        if (result instanceof ClientEntitySet) {
            index((ClientEntitySet) result);
        } else if (result instanceof Iterable) {
            index((Iterable<?>) result);
        } else {
            index(result);
        }
    }
}
