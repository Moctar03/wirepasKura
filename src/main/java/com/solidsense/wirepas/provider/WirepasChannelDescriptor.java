/**
 * Copyright (c) 2022 Sterwen-Technology and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Sterwen-Technology
 */
package com.solidsense.wirepas.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.kura.configuration.metatype.Option;
import org.eclipse.kura.core.configuration.metatype.Tad;
import org.eclipse.kura.core.configuration.metatype.Toption;
import org.eclipse.kura.core.configuration.metatype.Tscalar;
import org.eclipse.kura.driver.ChannelDescriptor;

/**
 * Wirepas specific channel descriptor. The descriptor contains the following
 * attribute definition identifier.
 *
 * "Endpoint" denotes the Wirepas endpoint /identifier
 */
public final class WirepasChannelDescriptor implements ChannelDescriptor {
    
    private static final String ENDPOINT = "endpoint"; 

    private static void addResourceNames(Tad target, List<String> values) {
        final List<Option> options = target.getOption();
        for (String value : values) {
            Toption option = new Toption();
            option.setLabel(value);
            option.setValue(value);
            options.add(option);
        }
    }
    
    @Override
    public Object getDescriptor() {
    
        final List<Tad> elements = new ArrayList<>();

        List<String> availablePins = new ArrayList<>();
        
        final Tad endpoint = new Tad();
        endpoint.setName(ENDPOINT);
        endpoint.setId(ENDPOINT);
        endpoint.setDescription(ENDPOINT);
        endpoint.setType(Tscalar.INTEGER);
        endpoint.setRequired(true);
        addResourceNames(endpoint, availablePins);
        elements.add(endpoint);
        
        return elements;
    }

    static String getEndpoint(Map<String, Object> properties) {
        return (String) properties.get(ENDPOINT);
    }
}
