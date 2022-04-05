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

import static java.util.Objects.requireNonNull;
import static org.eclipse.kura.channel.ChannelFlag.FAILURE;
import static org.eclipse.kura.channel.ChannelFlag.SUCCESS;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraRuntimeException;
import org.eclipse.kura.channel.ChannelRecord;
import org.eclipse.kura.channel.ChannelStatus;
import org.eclipse.kura.channel.listener.ChannelListener;
import org.eclipse.kura.cloudconnection.listener.CloudConnectionListener;
import org.eclipse.kura.cloudconnection.listener.CloudDeliveryListener;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.publisher.CloudPublisher;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.driver.ChannelDescriptor;
import org.eclipse.kura.driver.Driver;
import org.eclipse.kura.driver.PreparedRead;
import org.eclipse.kura.message.KuraPayload;
import org.eclipse.kura.type.DataType;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.util.base.TypeUtil;
import org.freedesktop.dbus.DBusMatchRule;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.messages.DBusSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The Class {@link WirepasDriver} is a Wirepas Driver implementation for
 * Kura Asset-Driver Topology.
 * <br/>
 * <br/>
 * This Wirepas Driver can be used in cooperation with Kura Asset Model and in
 * isolation as well. In case of isolation, the properties needs to be provided
 * externally.
 * <br/>
 * <br/>
 * The required properties are enlisted in {@link WirepasChannelDescriptor}.
 *
 * @see WirepasDriver
 * @see WirepasChannelDescriptor
 *
 */
public final class WirepasDriver extends Thread implements Driver, CloudConnectionListener, CloudDeliveryListener, ConfigurableComponent {

    private static final Logger logger  = LoggerFactory.getLogger(WirepasDriver.class);
    private static final String WRITE_FAILED_MESSAGE = "Driver write operation failed";
    private static final String READ_FAILED_MESSAGE = "Driver read operation failed";
    private CloudPublisher cloudPublisher;
    private WirepasListener wirepasListener;
    private Set<WirepasListener> gpioListeners;
    private String nodeAddress;
    private boolean stop = false;
    
    public void setCloudPublisher(CloudPublisher cloudPublisher) {
        this.cloudPublisher = cloudPublisher;
        this.cloudPublisher.registerCloudConnectionListener(WirepasDriver.this);
        this.cloudPublisher.registerCloudDeliveryListener(WirepasDriver.this);
    }

    public void unsetCloudPublisher(CloudPublisher cloudPublisher) {
        this.cloudPublisher.unregisterCloudConnectionListener(WirepasDriver.this);
        this.cloudPublisher.unregisterCloudDeliveryListener(WirepasDriver.this);
        this.cloudPublisher = null;
    }
    
    protected synchronized void activate(final Map<String, Object> properties) {
        logger.debug("Activating GPIO Driver...");
        this.gpioListeners = new HashSet<>();
        nodeAddress = (String) properties.get("Node Address");
        this.start();
        logger.debug("Activating GPIO Driver... Done");
    }
    

    public void run() {
        this.wirepasListener = new WirepasListener(nodeAddress);
        try (DBusConnection connection = DBusConnection.getConnection(DBusConnection.DBusBusType.SYSTEM)) {
          // add our signal handler
          DBusMatchRule signalRule = new DBusMatchRule("signal", "com.wirepas.sink.data1", "MessageReceived", "/com/wirepas/sink");
       // just do some sleep so you can see the events on stdout (you would probably do something else here)
          connection.addGenericSigHandler(signalRule, wirepasListener);
          while (!stop) {
              try {
                  Thread.sleep(1000L);
              } catch (Exception e) {
                  logger.info("Sleep Thread");
              }
          }
          connection.removeGenericSigHandler(signalRule, wirepasListener);
          connection.disconnect();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
    
    protected synchronized void deactivate() {
        logger.debug("Deactivating GPIO Driver...");
        doDeactivate();
        logger.debug("Deactivating GPIO Driver... Done");
    }

    protected synchronized void update(final Map<String, Object> properties) {
        logger.debug("Updating GPIO Driver...");
        nodeAddress = (String) properties.get("Node Address");
        logger.debug("Updating GPIO Driver... Done");
        this.wirepasListener.setChannelName(nodeAddress);
    }

    private void doDeactivate() {
        stop = true;
    }

    @Override
    public void connect() throws ConnectionException {
        // Not implemented
    }

    @Override
    public synchronized void disconnect() throws ConnectionException {
        doDeactivate();
    }

    @Override
    public ChannelDescriptor getChannelDescriptor() {
        return new WirepasChannelDescriptor();
    }

    @Override
    public synchronized void read(final List<ChannelRecord> records) throws ConnectionException {
        KuraPayload payload = new KuraPayload();
        payload.setTimestamp(new Date());
        for (final ChannelRecord record : records) {
            String endpoint =  (String) record.getChannelConfig().get("endpoint");
            String channelName = (String) record.getChannelConfig().get("+name");
            final Optional<TypedValue<?>> typedValue = this.wirepasListener.getValue(endpoint, record.getValueType());
            if (typedValue.isPresent()) {
                record.setValue(typedValue.get());
                payload.addMetric(channelName, typedValue.get());
                record.setChannelStatus(new ChannelStatus(SUCCESS));
                record.setTimestamp(System.currentTimeMillis());
            } else {
                record.setChannelStatus(new ChannelStatus(FAILURE, READ_FAILED_MESSAGE, null));
                record.setTimestamp(System.currentTimeMillis());
            }
        }
        if (this.cloudPublisher == null) {
            logger.info("No cloud publisher selected. Cannot publish!");
            return;
        }
        KuraMessage message = new KuraMessage(payload);
        // Publish the message
        try {
            this.cloudPublisher.publish(message);
            logger.info("Published message: {}", payload);
        } catch (Exception e) {
            logger.error("Cannot publish message: {}", message, e);
        }
    }

    @Override
    public synchronized void write(final List<ChannelRecord> records) throws ConnectionException {
        logger.warn(WRITE_FAILED_MESSAGE);
        throw new KuraRuntimeException(KuraErrorCode.OPERATION_NOT_SUPPORTED, "write");
    }

    @Override
    public synchronized PreparedRead prepareRead(List<ChannelRecord> channelRecords) {
        requireNonNull(channelRecords, "Channel Record list cannot be null");
        logger.warn(READ_FAILED_MESSAGE);
        return null;
    }

    @Override
    public synchronized void registerChannelListener(final Map<String, Object> channelConfig,
            final ChannelListener listener) throws ConnectionException {
        String channelName = (String) channelConfig.get("+name");
        WirepasListener gpioListener = new WirepasListener(channelName);

        this.gpioListeners.add(gpioListener);
        
    }

    @Override
    public synchronized void unregisterChannelListener(final ChannelListener listener) throws ConnectionException {
        Iterator<WirepasListener> iterator = this.gpioListeners.iterator();
        logger.info(iterator.toString());
    }

    private Optional<TypedValue<?>> getTypedValue(final DataType expectedValueType, final Object containedValue) {
        try {
            if (containedValue == null) {
                return Optional.empty();
            }
            switch (expectedValueType) {
            case LONG:
                return Optional.of(TypedValues.newLongValue(new BigInteger((byte[]) containedValue).longValue()));
            case FLOAT:
                return Optional.of(TypedValues.newFloatValue(new BigInteger((byte[]) containedValue).floatValue()));
            case DOUBLE:
                return Optional.of(TypedValues.newDoubleValue(new BigInteger((byte[]) containedValue).doubleValue()));
            case INTEGER:
                return Optional.of(TypedValues.newIntegerValue(new BigInteger((byte[]) containedValue).intValue()));
            case BOOLEAN:
                int expectedValue = new BigInteger((byte[]) containedValue).intValue();
                return Optional.of(TypedValues.newBooleanValue((expectedValue > 0) ? true : false));
                
            case STRING:
                return Optional.of(TypedValues.newStringValue(new String((byte[]) containedValue)));
            case BYTE_ARRAY:
                return Optional.of(TypedValues.newByteArrayValue(TypeUtil.objectToByteArray(containedValue)));
            default:
                return Optional.empty();
            }
        } catch (final Exception ex) {
            logger.error("Error while converting the retrieved value to the defined typed", ex);
            return Optional.empty();
        }
    }

    public class WirepasListener implements DBusSigHandler<DBusSignal> {

        private String channelName;
        private HashMap<String, Object> wirepasListeners;

        public WirepasListener(String channelName) {
            this.channelName = channelName;
            this.wirepasListeners = new HashMap<>();
        }

        @Override
        public void handle(DBusSignal s) {
            try {
                String srcAddress = String.valueOf(s.getParameters()[1]);
                if (srcAddress.equalsIgnoreCase(channelName)) {
                    String srcEp = String.valueOf(s.getParameters()[3]);
                    Object bytesArr = s.getParameters()[8];
                    wirepasListeners.put(srcEp, bytesArr);
                }
            } catch (DBusException e) { 
                e.printStackTrace();
            }
        }
        
        public void setChannelName(String newchannelName) {
           this.channelName = newchannelName;
        }
        
        public Optional<TypedValue<?>> getValue(String endpoint, DataType expectedValueType) {
            final Optional<TypedValue<?>> typedValue = getTypedValue(expectedValueType, wirepasListeners.get(endpoint));
            return typedValue;
        }
    }

    @Override
    public void onMessageConfirmed(String messageId) {
    }

    @Override
    public void onDisconnected() {
    }

    @Override
    public void onConnectionLost() {
    }

    @Override
    public void onConnectionEstablished() {
    }
}