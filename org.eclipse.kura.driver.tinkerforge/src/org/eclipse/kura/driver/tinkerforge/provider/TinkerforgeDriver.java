/*******************************************************************************
 * Copyright (c) 2018 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.kura.driver.tinkerforge.provider;

import static java.util.Objects.requireNonNull;
import static org.eclipse.kura.channel.ChannelFlag.FAILURE;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.channel.ChannelFlag;
import org.eclipse.kura.channel.ChannelRecord;
import org.eclipse.kura.channel.ChannelStatus;
import org.eclipse.kura.channel.listener.ChannelListener;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.driver.ChannelDescriptor;
import org.eclipse.kura.driver.Driver;
import org.eclipse.kura.driver.PreparedRead;
import org.eclipse.kura.type.DataType;
import org.eclipse.kura.type.TypedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tinkerforge.Device;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;

public abstract class TinkerforgeDriver<D extends Device, TCD extends TinkerforgeChannelDescriptor>
		implements Driver, ConfigurableComponent {

	protected static final ChannelStatus SUCCESS = new ChannelStatus(ChannelFlag.SUCCESS);

	protected static final Logger logger = LoggerFactory.getLogger(TinkerforgeDriver.class);

	private final ChannelListenerManager channelListenerManager = new ChannelListenerManager();
	private final ConnectionManager connectionManager = new ConnectionManager();

	private TinkerforgeDriverOptions options;

	private final Class<D> dClass;
	private final Class<TCD> tcdClass;

	protected TinkerforgeDriver(final Class<D> dClass, final Class<TCD> tcdClass) {
		this.dClass = dClass;
		this.tcdClass = tcdClass;
	}

	protected final D getDevice() {
		try {
			return dClass.getDeclaredConstructor(String.class, IPConnection.class).newInstance(options.getUuid(),
					connectionManager.getIpConnection());
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Override
	public ChannelDescriptor getChannelDescriptor() {
		try {
			return tcdClass.newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			return () -> "invalid object";
		}
	}

	protected abstract void readValues(final List<ChannelRecord> records)
			throws TimeoutException, NotConnectedException;

	protected abstract void writeValues(final List<ChannelRecord> records)
			throws TimeoutException, NotConnectedException;

	public final void activate(Map<String, Object> properties) {
		logger.info("activating...");

		updated(properties);

		logger.info("activating...done");
	}

	public final void deactivate() {
		logger.info("deactivating...");

		connectionManager.shutdown();

		logger.info("deactivating...done");
	}

	public final void updated(Map<String, Object> properties) {
		logger.info("updating..");

		this.options = new TinkerforgeDriverOptions(properties);

		this.connectionManager.setOptions(options);

		// some drivers might need to reconnect due to changes to connection parameters
		// in configuration, driver should not block the configuration update thread
		this.connectionManager.reconnectAsync();

		logger.info("updating...done");
	}

	@Override
	public final void connect() throws ConnectionException {
		connectionManager.connectSync();
	}

	@Override
	public final void disconnect() throws ConnectionException {
		connectionManager.disconnectSync();
	}

	@Override
	public final void registerChannelListener(Map<String, Object> channelConfig, ChannelListener listener)
			throws ConnectionException {
		this.channelListenerManager.registerChannelListener(channelConfig, listener);
		// the driver should try to connect to the remote device and start sending
		// notifications to the listener,
		// but it should avoid performing blocking operations in this method
		this.connectionManager.connectAsync();
	}

	@Override
	public final void unregisterChannelListener(ChannelListener listener) throws ConnectionException {
		this.channelListenerManager.unregisterChannelListener(listener);
	}

	@Override
	public final synchronized void read(final List<ChannelRecord> records) throws ConnectionException {
		logger.debug("reading...");

		// read() should trigger a connect()
		connect();

		// read new values
		try {
			readValues(records);
			// record success
			records.forEach(record -> record.setChannelStatus(SUCCESS));
		} catch (TimeoutException | NotConnectedException e) {
			// record failure
			final ChannelStatus status = new ChannelStatus(FAILURE, "failed to read channel", e);
			records.forEach(record -> record.setChannelStatus(status));
		} finally {
			final long timestamp = System.currentTimeMillis();
			records.forEach(record -> record.setTimestamp(timestamp));
		}

		logger.debug("reading...done");
	}

	@Override
	public final synchronized void write(final List<ChannelRecord> records) throws ConnectionException {
		logger.debug("writing...");

		// write() should trigger a connect()
		connect();

		// fix null
		records.forEach(record -> record.setValue(requireNonNull(record.getValue(), "supplied value cannot be null")));

		// write values
		try {
			writeValues(records);
			// record success
			records.forEach(record -> record.setChannelStatus(SUCCESS));
		} catch (TimeoutException | NotConnectedException e) {
			// record failure
			final ChannelStatus status = new ChannelStatus(FAILURE, "failed to write channel", e);
			records.forEach(record -> record.setChannelStatus(status));
		} finally {
			final long timestamp = System.currentTimeMillis();
			records.forEach(record -> record.setTimestamp(timestamp));
		}

		logger.debug("writing...done");
	}

	@Override
	public final synchronized PreparedRead prepareRead(final List<ChannelRecord> records) {
		return new PreparedReadImpl(records);
	}

	protected abstract TypedValue<?> readValue(final ChannelRecord record) throws TimeoutException, NotConnectedException;

	private class PreparedReadImpl implements PreparedRead {

		private final List<ChannelRecord> records;
		private final List<ReadRequest> validRequests;

		public PreparedReadImpl(final List<ChannelRecord> records) {
			// A driver can perform optimizations here to allow faster execution of
			// the read request. At minimum it should validate the supplied records
			// during the preparedRead() method, and convert the request parameters
			// into a more efficient representation than the channel configuration map
			this.records = records;
			this.validRequests = new ArrayList<>(records.size());
			for (final ChannelRecord record : records) {
				try {
					// records with valid configuration will be processed during the execute()
					// method
					validRequests.add(new ReadRequest(record));
				} catch (Exception e) {
					// requests with invalid configuration can be immediately marked as failed
					// invalid records should be returned by execute() anyway
					record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, e.getMessage(), e));
					record.setTimestamp(System.currentTimeMillis());
				}
			}
		}

		@Override
		public void close() throws Exception {
			// no need to close anything
		}

		@Override
		public List<ChannelRecord> execute() throws ConnectionException, KuraException {
			connect();

			for (final ReadRequest request : validRequests) {
				try {
					request.record.setValue(readValue(request.record));
					request.record.setChannelStatus(SUCCESS);
				} catch (Exception e) {
					request.record.setChannelStatus(new ChannelStatus(ChannelFlag.FAILURE, e.getMessage(), e));
				} finally {
					request.record.setTimestamp(System.currentTimeMillis());
				}
			}
			// returns all records, not only the ones with valid configuration
			return records;
		}

		@Override
		public List<ChannelRecord> getChannelRecords() {
			return records;
		}

	}

	static class BaseRequest {

        // these parameters can be retrieved from a ChannelRecord in a more convenient
        // way using the getChannelName() and getValueType() methods, however, when
        // a ChannelListener is registered a raw map is passed as argument
        private static final String CHANNEL_NAME_PROPERTY_KEY = "+name";
        private static final String CHANNEL_VALUE_TYPE_PROPERY_KEY = "+value.type";

        final String channelName;
        final Map<String, Object> channelConfig;
        final DataType valueType;

        public BaseRequest(final Map<String, Object> channelConfig) {
            this.channelName = (String) channelConfig.get(CHANNEL_NAME_PROPERTY_KEY);
            this.channelConfig = channelConfig;
            this.valueType = DataType.valueOf((String) channelConfig.get(CHANNEL_VALUE_TYPE_PROPERY_KEY));
        }
    }

	static final class ReadRequest extends BaseRequest {

		private final ChannelRecord record;

		public ChannelRecord getRecord() {
			return record;
		}

		public ReadRequest(final ChannelRecord record) {
            super(record.getChannelConfig());
			this.record = record;
		}
	}
}