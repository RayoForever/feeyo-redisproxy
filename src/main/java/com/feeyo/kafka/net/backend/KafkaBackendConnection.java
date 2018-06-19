package com.feeyo.kafka.net.backend;

import java.nio.channels.SocketChannel;

import com.feeyo.redis.net.backend.BackendConnection;

/**
 * Kafka Connection
 * 
 * @author zhuam
 *
 */
public class KafkaBackendConnection extends BackendConnection {

	public KafkaBackendConnection(boolean isZeroCopy, SocketChannel channel) {
		super(isZeroCopy, channel);
	}

}
