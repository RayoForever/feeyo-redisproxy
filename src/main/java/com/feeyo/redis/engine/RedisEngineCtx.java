package com.feeyo.redis.engine;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.kafka.config.KafkaPoolCfg;
import com.feeyo.kafka.net.backend.broker.offset.BrokerOffsetService;
import com.feeyo.net.nio.NIOAcceptor;
import com.feeyo.net.nio.NIOConnector;
import com.feeyo.net.nio.NIOReactor;
import com.feeyo.net.nio.NIOReactorPool;
import com.feeyo.net.nio.NetSystem;
import com.feeyo.net.nio.SystemConfig;
import com.feeyo.net.nio.buffer.BufferPool;
import com.feeyo.net.nio.buffer.bucket.BucketBufferPool;
import com.feeyo.redis.config.ConfigLoader;
import com.feeyo.redis.config.NetFlowCfg;
import com.feeyo.redis.config.PoolCfg;
import com.feeyo.redis.config.UserCfg;
import com.feeyo.redis.net.backend.pool.AbstractPool;
import com.feeyo.redis.net.backend.pool.PoolFactory;
import com.feeyo.redis.net.front.NetFlowGuard;
import com.feeyo.redis.net.front.RedisFrontendConnectionFactory;
import com.feeyo.redis.virtualmemory.VirtualMemoryService;
import com.feeyo.util.ExecutorUtil;
import com.feeyo.util.keepalived.KeepAlived;

public class RedisEngineCtx {
	
	private static Logger LOGGER = LoggerFactory.getLogger( RedisEngineCtx.class );

	final static RedisEngineCtx instance;

	static {
		instance = new RedisEngineCtx();
	}
	
	private VirtualMemoryService virtualMemoryService;
	private BufferPool bufferPool;	
	
	private volatile NetFlowGuard netflowGuard;
	
	// 
	private volatile Map<String, NIOReactor> reactorMap = new HashMap<String, NIOReactor>();
	
	private volatile Map<String, String> serverMap = null;
	private volatile Map<String, UserCfg> userMap = null;
	private volatile Map<Integer, PoolCfg> poolCfgMap = null;
	private volatile Map<Integer, AbstractPool> poolMap = null;
	private volatile Map<String, NetFlowCfg> netflowMap = null;
	
	private volatile Properties mailProperty = null;

	// backup
	private volatile  Map<Integer, AbstractPool> _poolMap = null;
	private volatile  Map<String, UserCfg> _userMap = null;
	private volatile  Map<String, String> _serverMap = null;
	private volatile Map<String, NetFlowCfg> _netflowMap = null;
	private volatile  Properties _mailProperty = null;
	
	private ReentrantLock lock;
	
	// 初始化
	public void init() throws Exception {
		
		this.lock = new ReentrantLock();

		//
		try {
			this.serverMap = ConfigLoader.loadServerMap( ConfigLoader.buidCfgAbsPathFor("server.xml") );
			this.poolCfgMap = ConfigLoader.loadPoolMap( ConfigLoader.buidCfgAbsPathFor("pool.xml") );
			this.userMap = ConfigLoader.loadUserMap(poolCfgMap, ConfigLoader.buidCfgAbsPathFor("user.xml") );
			this.netflowMap = ConfigLoader.loadNetFlowMap(ConfigLoader.buidCfgAbsPathFor("netflow.xml"));
			this.mailProperty = ConfigLoader.loadMailProperties(ConfigLoader.buidCfgAbsPathFor("mail.properties"));
		} catch (Exception e) {
			throw e;
		}
		
		// 1、Buffer 配置
		// ---------------------------------------------------------------------------		
	    String portString = this.serverMap.get("port");
        String reactorSizeString = this.serverMap.get("reactorSize");
        String minBufferSizeString = this.serverMap.get("minBufferSize");
        String maxBufferSizeString = this.serverMap.get("maxBufferSize");
        String decomposeBufferSizeString = this.serverMap.get("decomposeBufferSize");
        
        String minChunkSizeString = this.serverMap.get("minChunkSize"); 
        String incrementString = this.serverMap.get("increment"); 
        String maxChunkSizeString = this.serverMap.get("maxChunkSize"); 
        
        String bossSizeString = this.serverMap.get("bossSize");
        String timerSizeString = this.serverMap.get("timerSize"); 
        
        String frontSocketSoRcvbufString = this.serverMap.get("frontSocketSoRcvbuf"); 
        String frontSocketSoSndbufString = this.serverMap.get("frontSocketSoSndbuf"); 
        String backSocketSoRcvbufString = this.serverMap.get("backSocketSoRcvbuf"); 
        String backSocketSoSndbufString = this.serverMap.get("backSocketSoSndbuf"); 
       
        int port = portString == null ? 8066: Integer.parseInt( portString );
        
        int processors = Runtime.getRuntime().availableProcessors();
        int reactorSize = reactorSizeString == null ? processors + 1 : Integer.parseInt( reactorSizeString );
        if ( reactorSize > 9 ) {
        	reactorSize = 4 + (processors * 5 / 8);
        }
        
        long minBufferSize = minBufferSizeString == null ? 16384 * 1000 : Long.parseLong( minBufferSizeString );
        long maxBufferSize = maxBufferSizeString == null ? 16384 * 10000 : Long.parseLong( maxBufferSizeString );
        int decomposeBufferSize = decomposeBufferSizeString == null ? 64 * 1024 : Integer.parseInt( decomposeBufferSizeString ); 
        
        int minChunkSize = minChunkSizeString == null ? 0 : Integer.parseInt( minChunkSizeString ); 
        
        this.netflowGuard = new NetFlowGuard();
        this.netflowGuard.setCfgs( netflowMap );
        
		int[] increments = null;
		if ( incrementString == null ) {
			increments = new int[] { 1024 };
			
		} else {
			String[] incrementStrings = incrementString.split(",");
			if ( incrementStrings == null || incrementStrings.length == 0 ) {
				increments = new int[] { 1024 };
			} else {
				increments = new int[ incrementStrings.length ];
				for (int i = 0; i < incrementStrings.length; i++ ) {
					increments[i] = Integer.parseInt( incrementStrings[i]);
				}
			}
		}
        
        int maxChunkSize = maxChunkSizeString == null ? 64 * 1024 : Integer.parseInt( maxChunkSizeString ); 
        
        int bossSize = bossSizeString == null ? 10 : Integer.parseInt( bossSizeString ); 
        int timerSize = timerSizeString == null ? 6 : Integer.parseInt( timerSizeString ); 

        //PageBufferPool BucketBufferPool
        this.bufferPool = new BucketBufferPool(minBufferSize, maxBufferSize, decomposeBufferSize,
        		minChunkSize, increments, maxChunkSize);   
        
//        this.bufferPool = new PageBufferPool(minBufferSize, maxBufferSize, decomposeBufferSize,
//        		minChunkSize, increments, maxChunkSize);
       
        this.virtualMemoryService = new VirtualMemoryService();
        this.virtualMemoryService.start();
        
        new NetSystem(bufferPool, ExecutorUtil.create("BusinessExecutor-", bossSize), ExecutorUtil.create("TimerExecutor-", timerSize));
        
        String frontIdleTimeoutString = this.serverMap.get("frontIdleTimeout");
        String backendIdleTimeoutString = this.serverMap.get("backendIdleTimeout");
        int frontIdleTimeout = frontIdleTimeoutString == null ? 5 * 60 * 1000: Integer.parseInt( frontIdleTimeoutString );
        int backendIdleTimeout = backendIdleTimeoutString == null ? 30 * 60 * 1000: Integer.parseInt( backendIdleTimeoutString );
        
        int frontSocketSoRcvbuf = frontSocketSoRcvbufString == null ? 2097152 : Integer.parseInt( frontSocketSoRcvbufString ); 
        int frontSocketSoSndbuf = frontSocketSoSndbufString == null ? 4194304 : Integer.parseInt( frontSocketSoSndbufString ); 
        int backSocketSoRcvbuf = backSocketSoRcvbufString == null ? 4194304 : Integer.parseInt( backSocketSoRcvbufString ); 
        int backSocketSoSndbuf = backSocketSoSndbufString == null ? 4194304 : Integer.parseInt( backSocketSoSndbufString ); 
        // code safe
 		if ( frontSocketSoRcvbuf < 524288 ) frontSocketSoRcvbuf = 524288;
 		if ( frontSocketSoSndbuf < 524288 ) frontSocketSoSndbuf = 524288;
 		if ( backSocketSoRcvbuf < 524288 ) backSocketSoRcvbuf = 524288;
 		if ( backSocketSoSndbuf < 524288 ) backSocketSoSndbuf = 524288;
        
        SystemConfig systemConfig = new SystemConfig(frontSocketSoRcvbuf, frontSocketSoSndbuf, backSocketSoRcvbuf, backSocketSoSndbuf);
        systemConfig.setFrontIdleTimeout(  frontIdleTimeout );
        systemConfig.setBackendIdleTimeout( backendIdleTimeout );
        NetSystem.getInstance().setNetConfig( systemConfig );
        
        // output
        System.out.println( String.format("processors=%s, reactorSize=%s, bossSize=%s, timerSize=%s, frontIdleTimeout=%s, backendIdleTimeout=%s", 
        		processors, reactorSize, bossSize, timerSize, frontIdleTimeout, backendIdleTimeout) );
        
        
        // 2、 NIO 反应器配置 
		// ---------------------------------------------------------------------------
        NIOReactorPool reactorPool = new NIOReactorPool(BufferPool.LOCAL_BUF_THREAD_PREX + "NioReactor", reactorSize);        
        NIOReactor[] reactors = reactorPool.getAllReactors();
        for (NIOReactor r : reactors) {
			this.reactorMap.put(r.getName(), r);
		}
        
		
		// 3、后端配置
        // ---------------------------------------------------------------------------
        NIOConnector connector = new NIOConnector("NIOConnector", reactorPool);
        connector.start();
        NetSystem.getInstance().setConnector(connector);     
        
        
        
		// 4、后端物理连接池
		// ---------------------------------------------------------------------------
        boolean isKafkaPoolExist = false;
        
		this.poolMap = new HashMap<Integer, AbstractPool>( poolCfgMap.size() );
		for (final PoolCfg poolCfg : poolCfgMap.values()) {
			AbstractPool pool = PoolFactory.createPoolByCfg(poolCfg);
			pool.startup();
			this.poolMap.put(pool.getId(), pool);
			
			if ( poolCfg instanceof KafkaPoolCfg ) {
				isKafkaPoolExist = true;
			}
		}
		
        // 4.1 KafkaPoolCfg  加载 offset service
		if ( isKafkaPoolExist == true && !BrokerOffsetService.INSTANCE().isRunning() ) {
			BrokerOffsetService.INSTANCE().start();
	        Runtime.getRuntime().addShutdownHook(new Thread() {
				public void run() {
					BrokerOffsetService.INSTANCE().stop();
				}
			});
		}
		
        
        // 5、前端配置, 开启对外提供服务
        // ---------------------------------------------------------------------------
        NIOAcceptor acceptor = new NIOAcceptor("Server", "0.0.0.0", port, new RedisFrontendConnectionFactory(), reactorPool);
        acceptor.start();
        LOGGER.info( acceptor.getName() + " is started and listening on {}", acceptor.getPort());
        
        
        // 6, keepalive hook
        Iterator<String> it = userMap.keySet().iterator();
        String authString  = it.hasNext() ? it.next() : "";
        KeepAlived.check(port, authString);
	}
	
	
	public byte[] reloadAll() {
		
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			
			// 前置处理, 历史的 pool 做一次强制清除
			// ----------------------------------------------------
			if ( this._poolMap != null ) {
				for (final AbstractPool pool : _poolMap.values()) {	
					pool.close( true );
				}
			}
			
			// 1、加载 user.xml / server.xml / pool.xml
			Map<String, String> newServerMap = ConfigLoader.loadServerMap( ConfigLoader.buidCfgAbsPathFor("server.xml") );
			Map<Integer, PoolCfg> newPoolCfgMap = ConfigLoader.loadPoolMap( ConfigLoader.buidCfgAbsPathFor("pool.xml") );
			Map<String, UserCfg> newUserMap = ConfigLoader.loadUserMap(newPoolCfgMap, ConfigLoader.buidCfgAbsPathFor("user.xml") );
			Map<String, NetFlowCfg> newNetflowMap = this.netflowMap = ConfigLoader.loadNetFlowMap(ConfigLoader.buidCfgAbsPathFor("netflow.xml"));
			Properties newMailProperty = ConfigLoader.loadMailProperties(ConfigLoader.buidCfgAbsPathFor("mail.properties"));
			
			// 2、用户自检
			for( UserCfg userCfg: newUserMap.values() ) {
				
				int selectDb = userCfg.getSelectDb();
				if ( selectDb < 0 || selectDb > 12 ) {
					LOGGER.error("selfCheck err: user selectDb={} is error ", selectDb);
					return ("-ERR reload failed \r\n").getBytes();
				}
				
				int poolId = userCfg.getPoolId();
				PoolCfg poolCfg = newPoolCfgMap.get( poolId );
				if ( poolCfg == null ) {
					LOGGER.error("selfCheck err: {} pool does not exist ", poolId);
					return ("-ERR reload failed \r\n").getBytes();
				}
			}
			
			// 3 连接池自检 
			Map<Integer, AbstractPool> newPoolMap = new HashMap<Integer, AbstractPool>( newPoolCfgMap.size() );
			for (final PoolCfg poolCfg : newPoolCfgMap.values()) {
				AbstractPool pool = PoolFactory.createPoolByCfg(poolCfg);
				newPoolMap.put(pool.getId(), pool);
	        }
			
			boolean selfCheck1 =  true;
			for( AbstractPool pool: newPoolMap.values() ) {
				boolean isTest = pool.testConnection();
				if ( !isTest ) {
					selfCheck1 = false;
					break;
				}
			}
			
			// 4、备份 old, 切换 new、清理 old
			if ( selfCheck1 ) {
				// 启动
				for (final AbstractPool pool : newPoolMap.values()) {	
					pool.startup();
				}
				
				//备份 old
				this._userMap = userMap;
				this._poolMap = poolMap;
				this._serverMap = serverMap;
				this._netflowMap = netflowMap;
				this._mailProperty = mailProperty;
				
				
				//切换 new
				this.poolMap = newPoolMap;
				this.userMap = newUserMap;
				this.serverMap = newServerMap;
				this.netflowMap = newNetflowMap;
				this.mailProperty = newMailProperty;
				
				// server.xml 部分设置生效
				String frontIdleTimeoutString = this.serverMap.get("frontIdleTimeout");
		        String backendIdleTimeoutString = this.serverMap.get("backendIdleTimeout");
		        int frontIdleTimeout = frontIdleTimeoutString == null ? 5 * 60 * 1000: Integer.parseInt( frontIdleTimeoutString );
		        int backendIdleTimeout = backendIdleTimeoutString == null ? 30 * 60 * 1000: Integer.parseInt( backendIdleTimeoutString );
		        
		        String frontSocketSoRcvbufString = this.serverMap.get("frontSocketSoRcvbuf"); 
		        String frontSocketSoSndbufString = this.serverMap.get("frontSocketSoSndbuf"); 
		        String backSocketSoRcvbufString = this.serverMap.get("backSocketSoRcvbuf"); 
		        String backSocketSoSndbufString = this.serverMap.get("backSocketSoSndbuf"); 
		        int frontSocketSoRcvbuf = frontSocketSoRcvbufString == null ? 1048576 : Integer.parseInt( frontSocketSoRcvbufString ); 
		        int frontSocketSoSndbuf = frontSocketSoSndbufString == null ? 4194304 : Integer.parseInt( frontSocketSoSndbufString ); 
		        int backSocketSoRcvbuf = backSocketSoRcvbufString == null ? 4194304 : Integer.parseInt( backSocketSoRcvbufString ); 
		        int backSocketSoSndbuf = backSocketSoSndbufString == null ? 1048576 : Integer.parseInt( backSocketSoSndbufString ); 
		        // code safe
		 		if ( frontSocketSoRcvbuf < 524288 ) frontSocketSoRcvbuf = 524288;
		 		if ( frontSocketSoSndbuf < 524288 ) frontSocketSoSndbuf = 524288;
		 		if ( backSocketSoRcvbuf < 524288 ) backSocketSoRcvbuf = 524288;
		 		if ( backSocketSoSndbuf < 524288 ) backSocketSoSndbuf = 524288;
		        
		        SystemConfig systemConfig = new SystemConfig(frontSocketSoRcvbuf, frontSocketSoSndbuf, backSocketSoRcvbuf, backSocketSoSndbuf);
		        systemConfig.setFrontIdleTimeout(  frontIdleTimeout );
		        systemConfig.setBackendIdleTimeout( backendIdleTimeout );
		        NetSystem.getInstance().setNetConfig( systemConfig );

	            //清理 old
				for (final AbstractPool pool : _poolMap.values()) {	
					pool.close( false );
				}
				return "+OK\r\n".getBytes();
				
			} else  {
				return "-ERR reload failed. \r\n".getBytes();
			}
		} catch(Exception e) {
			LOGGER.error("reload err:", e);
			return "-ERR reload failed. \r\n".getBytes();			
		} finally {
			lock.unlock();
		}		
	}
	
	// reload netflow
	public byte[] reloadNetflow() {		
		
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			Map<String, NetFlowCfg> newNetflowMap = ConfigLoader.loadNetFlowMap( ConfigLoader.buidCfgAbsPathFor("netflow.xml") );
			
			// 自检
			for( NetFlowCfg netflowCfg: newNetflowMap.values() ) {
				String pwd = netflowCfg.getPassword();
				if ( userMap.get( pwd ) == null ) {
					LOGGER.error("##self check err: {} user does not exist ", pwd);
					return ("-ERR reload failed \r\n").getBytes();
				} 
			}
			// 备份 old
			this._netflowMap = netflowMap;
			
			// 切换 new
			this.netflowMap = newNetflowMap;	
			
			// 更新 netflow
			if ( this.netflowGuard != null ) {
				this.netflowGuard.setCfgs( this.netflowMap );
			}
			
		} catch (Exception e) {
			StringBuffer sb = new StringBuffer();
			sb.append("-ERR ").append(e.getMessage()).append("\r\n");
			return sb.toString().getBytes();
		} finally {
			lock.unlock();
		}		
		return "+OK\r\n".getBytes();
	}
	
	public byte[] reloadUser() {		
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			Map<String, UserCfg> newUserMap = ConfigLoader.loadUserMap(poolCfgMap, ConfigLoader.buidCfgAbsPathFor("user.xml") );
			
			// 自检
			for( UserCfg userCfg: newUserMap.values() ) {
				int poolId = userCfg.getPoolId();
				PoolCfg poolCfg = poolCfgMap.get( poolId );
				if ( poolCfg == null ) {
					LOGGER.error("##self check err: {} connection pool does not exist ", poolId);
					return ("-ERR reload failed \r\n").getBytes();
				} 
			}
			// 备份 old
			this._userMap = userMap;
			
			// 切换 new
			this.userMap = newUserMap;	
			
		} catch (Exception e) {
			StringBuffer sb = new StringBuffer();
			sb.append("-ERR ").append(e.getMessage()).append("\r\n");
			return sb.toString().getBytes();
		} finally {
			lock.unlock();
		}		
		return "+OK\r\n".getBytes();
	}

	public byte[] reloadServer() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			// 1. 加载 server.xml
			Map<String, String> newServerMap = ConfigLoader.loadServerMap(ConfigLoader.buidCfgAbsPathFor("server.xml"));

			// 2. 切换到新 server
			this.serverMap = newServerMap;

			// 3. 生效部分 server.xml 配置
			String frontIdleTimeoutString = this.serverMap.get("frontIdleTimeout");
			String backendIdleTimeoutString = this.serverMap.get("backendIdleTimeout");
			int frontIdleTimeout = frontIdleTimeoutString == null ? 5 * 60 * 1000: Integer.parseInt( frontIdleTimeoutString );
			int backendIdleTimeout = backendIdleTimeoutString == null ? 30 * 60 * 1000: Integer.parseInt( backendIdleTimeoutString );
			
			String frontSocketSoRcvbufString = this.serverMap.get("frontSocketSoRcvbuf");
			String frontSocketSoSndbufString = this.serverMap.get("frontSocketSoSndbuf");
			String backSocketSoRcvbufString = this.serverMap.get("backSocketSoRcvbuf");
			String backSocketSoSndbufString = this.serverMap.get("backSocketSoSndbuf");
			int frontSocketSoRcvbuf = frontSocketSoRcvbufString == null ? 1048576 : Integer.parseInt(frontSocketSoRcvbufString);
			int frontSocketSoSndbuf = frontSocketSoSndbufString == null ? 4194304 : Integer.parseInt(frontSocketSoSndbufString);
			int backSocketSoRcvbuf = backSocketSoRcvbufString == null ? 4194304 : Integer.parseInt(backSocketSoRcvbufString);
			int backSocketSoSndbuf = backSocketSoSndbufString == null ? 1048576 : Integer.parseInt(backSocketSoSndbufString);
			// code safe
			if (frontSocketSoRcvbuf < 524288) frontSocketSoRcvbuf = 524288;
			if (frontSocketSoSndbuf < 524288) frontSocketSoSndbuf = 524288;
			if (backSocketSoRcvbuf < 524288) backSocketSoRcvbuf = 524288;
			if (backSocketSoSndbuf < 524288) backSocketSoSndbuf = 524288;

			SystemConfig systemConfig = new SystemConfig(frontSocketSoRcvbuf, frontSocketSoSndbuf, backSocketSoRcvbuf, backSocketSoSndbuf);
			systemConfig.setFrontIdleTimeout(  frontIdleTimeout );
			systemConfig.setBackendIdleTimeout( backendIdleTimeout );
			NetSystem.getInstance().setNetConfig( systemConfig );

			// 4. 生效新的 ZK
//			ZkClient.INSTANCE().reloadZkCfg();
			
			return "+OK\r\n".getBytes();
		} catch (Exception e) {
			StringBuffer sb = new StringBuffer();
			sb.append("-ERR ").append(e.getMessage()).append("\r\n");
			return sb.toString().getBytes();
		} finally {
			lock.unlock();
		}
	}
	
	public byte[] reloadMailProperties() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			this.mailProperty = ConfigLoader.loadMailProperties(ConfigLoader.buidCfgAbsPathFor("mail.properties"));
			return "+OK\r\n".getBytes();
		} catch (Exception e) {
			StringBuffer sb = new StringBuffer();
			sb.append("-ERR ").append(e.getMessage()).append("\r\n");
			return sb.toString().getBytes();
		} finally {
			lock.unlock();
		}
	}

	public byte[] reloadPool() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			// 1. 加载 pool.xml
			Map<Integer, PoolCfg> newPoolCfgMap = ConfigLoader.loadPoolMap( ConfigLoader.buidCfgAbsPathFor("pool.xml") );

			// 2. 初始化新的 pool
			Map<Integer, AbstractPool> newPoolMap = new HashMap<Integer, AbstractPool>( newPoolCfgMap.size() );
			for (final PoolCfg poolCfg : newPoolCfgMap.values()) {
				AbstractPool pool = PoolFactory.createPoolByCfg(poolCfg);
				newPoolMap.put(pool.getId(), pool);
			}

			// 3. 新 pool 自检
			boolean poolCheck =  true;
			for( AbstractPool pool: newPoolMap.values() ) {
				boolean isTest = pool.testConnection();
				if ( !isTest ) {
					poolCheck = false;
					break;
				}
			}

			// 4. 切换到新 pool
			if ( poolCheck ) {
				// 启动新 pool
				for (final AbstractPool pool : newPoolMap.values()) {
					pool.startup();
				}

				this._poolMap = poolMap;
				this.poolMap = newPoolMap;

				// 清理旧 pool
				for (final AbstractPool pool : _poolMap.values()) {
					pool.close( false );
				}
				return "+OK\r\n".getBytes();
			} else {
				LOGGER.error("reload pool failed");
				return "-ERR reload pool failed\r\n".getBytes();
			}
		} catch (Exception e) {
			StringBuffer sb = new StringBuffer();
			sb.append("-ERR ").append(e.getMessage()).append("\r\n");
			return sb.toString().getBytes();
		} finally {
			lock.unlock();
		}
	}

	public NIOReactor findReactor(String name) {
		return reactorMap.get(name);
	}

	public Map<String, NIOReactor> getReactorMap() {
		return this.reactorMap;
	}
	
	public BufferPool getBufferPool() {
		return this.bufferPool;
	}
	
	public Map<Integer, AbstractPool> getPoolMap() {
		return this.poolMap;
	}
	
	public Map<String, String> getServerMap() {
		return this.serverMap;
	}

	public Map<String, UserCfg> getUserMap() {
		return this.userMap;
	}
	
	public Properties getMailProperties() {
		return this.mailProperty;
	}
	
	public Map<Integer, AbstractPool> getBackupPoolMap() {
		return this._poolMap;
	}
	
	public Map<String, UserCfg> getBackupUserMap() {
		return this._userMap;
	}

	public Map<Integer, PoolCfg> getPoolCfgMap() {
		return poolCfgMap;
	}

	public Map<String, String> getBackupServerMap() {
		return this._serverMap;
	}
	
	public Map<String, NetFlowCfg> getBackupNetflowMap() {
		return this._netflowMap;
	}

	public Properties getBackupMailProperties() {
		return this._mailProperty;
	}
	
	public static RedisEngineCtx INSTANCE() {
		return instance;
	}

	public VirtualMemoryService getVirtualMemoryService() {
		return virtualMemoryService;
	}

	public NetFlowGuard getNetflowGuard() {
		return netflowGuard;
	}

}
