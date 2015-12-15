/**
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at
 
    http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License. 

*/

package org.apache.jmeter.protocol.mqttws.client;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.protocol.mqttws.control.gui.MQTTPublisherGui;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.BinaryCodec;
import org.apache.commons.codec.binary.Hex;



import org.eclipse.paho.client.mqttv3.MqttAsyncClient;


public class MqttPubSub extends AbstractJavaSamplerClient implements Serializable, MqttCallback {
	private static final long serialVersionUID = 1L;
	private MqttAsyncClient client;
	public int numSeq=0;
	public int quality = 0;
	private String myname = this.getClass().getName();
	private String host ;
	private String clientId ;
	private int throttle=0;
	private int acksTimeout = 5000;
	private MqttConnectOptions options = new MqttConnectOptions();
	private int timeout=30000;
	private boolean reconnectOnConnLost = true;
	private String heartbeatChannel = "user/72353640-8f4a-102b-8b12-99c200cfc5b7/device-333/request";
	Timer heartbeatTimer = new Timer("HeartBeat Timer");
	private HashMap<Integer, Boolean> SentMsgsAcksMap = new HashMap<Integer, Boolean>();
	private HashMap<Integer, String> InMsgsMap = new HashMap<Integer, String>();
	private HashMap<Integer, String> OutMsgsMap = new HashMap<Integer, String>();
	
	
	
	//common amongst objects
	private static final Logger log = LoggingManager.getLoggerForClass();

	@Override
	public Arguments getDefaultParameters() {
		Arguments defaultParameters = new Arguments();
		defaultParameters.addArgument("HOST", "tcp://localhost:1883");
		defaultParameters.addArgument("CLIENT_ID", "${__time(YMDHMS)}${__threadNum}");
		defaultParameters.addArgument("TOPIC", "TEST.MQTT");
		defaultParameters.addArgument("AGGREGATE", "10");
		defaultParameters.addArgument("CLEAN_SESSION", "false");
		return defaultParameters;
	}

	public void setupTest(JavaSamplerContext context){
		//nothing yet
	}
	
	public void delayedSetupTest(JavaSamplerContext context){
		log.debug(myname + ">>>> in setupTest");
		host = context.getParameter("HOST");
		throttle = Integer.parseInt((context.getParameter("PUBLISHER_THROTTLE")));
		acksTimeout = Integer.parseInt((context.getParameter("PUBLISHER_ACKS_TIMEOUT"))); 
		//System.out.println("Publisher acks timeout: " + acksTimeout);
		clientId = context.getParameter("CLIENT_ID");
		if("TRUE".equalsIgnoreCase(context.getParameter("RANDOM_SUFFIX"))){
			clientId= MqttPubSub.getClientId(clientId,Integer.parseInt(context.getParameter("SUFFIX_LENGTH")));	
		}
		try {
			log.debug("Host: " + host + "clientID: " + clientId);
			client = new MqttAsyncClient(host, clientId, new MemoryPersistence());
		} catch (MqttException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		//options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		//options.setCleanSession(false);
		options.setCleanSession(Boolean.parseBoolean((context.getParameter("CLEAN_SESSION"))));
		//System.out.println("Pubs cleansession ====> " + context.getParameter("CLEAN_SESSION"));
		options.setKeepAliveInterval(0);
		timeout = Integer.parseInt((context.getParameter("CONNECTION_TIMEOUT")));
		myname = context.getParameter("SAMPLER_NAME");
		String user = context.getParameter("USER"); 
		String pwd = context.getParameter("PASSWORD");
		if (user != null) {
			options.setUserName(user);
			if ( pwd!=null ) {
				options.setPassword(pwd.toCharArray());
			}
		}
	
		clientConnect(timeout);
		
		client.setCallback(this);
	}

	private boolean clientConnect(int conntimeout){
		//System.out.println("Publisher connecting.............................");
		if (client.isConnected()) {
			return true;
		}
		try {
			IMqttToken token = client.connect(options);
			token.waitForCompletion(conntimeout);
		} catch (MqttSecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return client.isConnected();
	}
	
	
	public SampleResult runTest(JavaSamplerContext context) {
		SentMsgsAcksMap.clear();
		delayedSetupTest(context);
		
		SampleResult result = new SampleResult();
		result.setSampleLabel(myname);
		//be optimistic - will set an error if we find one
		result.setResponseOK();
		if (!client.isConnected() ) {
			log.warn( myname + " >>>> Publisher is not connected - Retrying once more...");
			if (!this.clientConnect(timeout/2)) {
				log.error( myname + " >>>> Publisher is not connected - Aborting test");
				result.setResponseMessage("Cannot connect to broker: "+ client.getServerURI() );
				result.setResponseCode("FAILED");
				result.setSuccessful(false);
				result.setSamplerData("ERROR: Could not connect to broker: " + client.getServerURI());
				return result;
			}
		}
		startHeartBeat(heartbeatTimer);
		result.sampleStart(); // start stopwatch
		try {
			produce(context);
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		//this might not make much sense
		if (quality==0) {
			
		}
		if ( (quality>0) && (getNumMsgsDelivered()!= getNumMsgsSent() ) ) {
			result.setResponseMessage("ERROR: Was expecting "+ getNumMsgsSent() +" ACKS. Got only " + getNumMsgsDelivered() + " (Broker: " + client.getServerURI() + ")"  );
			result.setResponseCode("FAILED");
			result.setSuccessful(false);
			result.setSamplerData("ERROR: Did not get acks for all of my published messages");
		}
		
		result.sampleEnd(); 
		result.setSamplerData("Published " + getNumMsgsSent() + " messages" + 
				"\nGot ack for: " + getNumMsgsDelivered() +
				"\nTopic: " + context.getParameter("TOPIC") +
				"\nQoS: " + quality +
				"\nBroker: " + host +
				"\nMy client ID: " + clientId);
		
		log.info(myname + ">>>> ending runTest");
		return result;
	
	}

	public long getNumMsgsDelivered() {
		long numMsgsDelivered = 0;
		for (int key: SentMsgsAcksMap.keySet() ) {
			if (SentMsgsAcksMap.get(key)) {
				numMsgsDelivered++;
			}
		}
		return numMsgsDelivered;
	}
	
	public long getNumMsgsSent() {
		return SentMsgsAcksMap.size();
	}
	
	public void close(JavaSamplerContext context) {
		heartbeatTimer.cancel();
		try {
			client.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		flushMessages();
		
	}
	
	private static final String mycharset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
	public static String getClientId(String clientPrefix, int suffixLength) {
	    Random rand = new Random(System.nanoTime()*System.currentTimeMillis());
	    StringBuilder sb = new StringBuilder();
	    sb.append(clientPrefix);
	    for (int i = 0; i < suffixLength; i++) {
	        int pos = rand.nextInt(mycharset.length());
	        sb.append(mycharset.charAt(pos));
	    }
	    return sb.toString();
	}

	private boolean connecting=false;
	@Override
	public void connectionLost(Throwable arg0) {
		if ( reconnectOnConnLost && !connecting) {
			connecting=true;
			//System.out.println(myname + " WARNING: Publisher client connection was lost. Reason: "+ arg0.getMessage() + ". Will try reconnection.");
			log.warn(myname + " WARNING: Publisher client connection was lost. Reason: "+ arg0.getMessage() + ". Will try reconnection...");
			//System.out.println("#################################");
			clientConnect(timeout/2);
			connecting=false;
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		if (SentMsgsAcksMap.containsKey(token.getMessageId())) {
			SentMsgsAcksMap.put(token.getMessageId(), true);
		}
		//System.out.println("Delivery complete for Msg with Topic: " + token.getTopics()[0]);
		//System.out.println("Delivery complete for Msg with ID: " + token.getMessageId());
	}

	@Override
	public void messageArrived(String str, MqttMessage msg) throws Exception {
		System.out.println("Got message: (id= " + msg.getId() + ")" + msg.toString() + "\n");
		InMsgsMap.put(msg.getId(), msg.toString());
	}
	
	
private void produce(JavaSamplerContext context) throws Exception {
		
		// ---------------------Type of message -------------------//

		if ("FIXED".equals(context.getParameter("TYPE_MESSAGE"))) {
			log.error("Unimplemented: Fixed - TODO");
			/*
			produce(context.getParameter("MESSAGE"),
					context.getParameter("TOPIC"),
					Integer.parseInt(context.getParameter("AGGREGATE")),
					context.getParameter("QOS"),
					context.getParameter("RETAINED"),
					context.getParameter("TIME_STAMP"),
					context.getParameter("NUMBER_SEQUENCE"),					
					context.getParameter("TYPE_VALUE"),
					context.getParameter("FORMAT"),
					context.getParameter("CHARSET"),
					context.getParameter("LIST_TOPIC"),
					context.getParameter("STRATEGY"),
					context.getParameter("PER_TOPIC"));
*/
		} else if ("RANDOM".equals(context.getParameter("TYPE_MESSAGE"))) {

			log.error("Unimplemented: Randomly - TODO");
			/*produceRandomly(context.getParameter("SEED"),context.getParameter("MIN_RANDOM_VALUE"),
					context.getParameter("MAX_RANDOM_VALUE"),context.getParameter("TYPE_RANDOM_VALUE"),
					context.getParameter("TOPIC"),Integer.parseInt(context.getParameter("AGGREGATE")),
					context.getParameter("QOS"),context.getParameter("RETAINED"),
					context.getParameter("TIME_STAMP"),context.getParameter("NUMBER_SEQUENCE"),
					context.getParameter("TYPE_VALUE"),
					context.getParameter("FORMAT"),
					context.getParameter("CHARSET"),
					context.getParameter("LIST_TOPIC"),
					context.getParameter("STRATEGY"),
					context.getParameter("PER_TOPIC"));
					*/
									
		} else if ("TEXT".equals(context.getParameter("TYPE_MESSAGE"))) {
			produce(context.getParameter("MESSAGE"),
					context.getParameter("TOPIC"),
					Integer.parseInt(context.getParameter("AGGREGATE")),
					context.getParameter("QOS"),
					context.getParameter("RETAINED"),
					context.getParameter("TIME_STAMP"),
					context.getParameter("NUMBER_SEQUENCE"),					
					context.getParameter("TYPE_VALUE"),
					context.getParameter("FORMAT"),
					context.getParameter("CHARSET"),
					//context.getParameter("LIST_TOPIC"),
					"FALSE",
					context.getParameter("STRATEGY"),
					context.getParameter("PER_TOPIC"));
		} 
		else if ("TEXT_POOL".equals(context.getParameter("TYPE_MESSAGE"))) {
			produce(context.getParameter("MESSAGE"),
					context.getParameter("TOPIC"),
					Integer.parseInt(context.getParameter("AGGREGATE")),
					context.getParameter("QOS"),
					context.getParameter("RETAINED"),
					context.getParameter("TIME_STAMP"),
					context.getParameter("NUMBER_SEQUENCE"),					
					context.getParameter("TYPE_VALUE"),
					context.getParameter("FORMAT"),
					context.getParameter("CHARSET"),
					//context.getParameter("LIST_TOPIC"),
					"FALSE",
					context.getParameter("STRATEGY"),
					context.getParameter("PER_TOPIC"));
		}
		else if("BYTE_ARRAY".equals(context.getParameter("TYPE_MESSAGE"))){
			log.error("Unimplemented: Byte Array - TODO");
			/*
			produceBigVolume(
					context.getParameter("TOPIC"),
					Integer.parseInt(context.getParameter("AGGREGATE")),
					context.getParameter("QOS"),
					context.getParameter("RETAINED"),
					context.getParameter("TIME_STAMP"),
					context.getParameter("NUMBER_SEQUENCE"),					
					context.getParameter("FORMAT"),
					context.getParameter("CHARSET"),
					context.getParameter("SIZE_ARRAY"),
					context.getParameter("LIST_TOPIC"),
					context.getParameter("STRATEGY"),
					context.getParameter("PER_TOPIC"));			
					*/
		}

	}


	private void produce(String message, String topic, int aggregate,
			String qos, String isRetained, String useTimeStamp, String useNumberSeq,String type_value, String format, String charset,String isListTopic,String strategy,String isPerTopic) throws Exception {
		//System.out.println(myname + ">>>> Starting publishing on topic: " + topic);
		log.info(myname + ">>>> Starting publishing on topic: " + topic);
		try {
			// Quality
			if (MQTTPublisherGui.EXACTLY_ONCE.equals(qos)) {
				quality = 2;
			} else if (MQTTPublisherGui.AT_LEAST_ONCE.equals(qos)) {
				quality = 1;
			} else if (MQTTPublisherGui.AT_MOST_ONCE.equals(qos)) {
				quality = 0;
			}
			client.subscribe(topic, quality);
			// Retained
			boolean retained = false;
			if ("TRUE".equals(isRetained))
				retained = true;
			// List topic
			if("FALSE".equals(isListTopic)){		
				for (int i = 0; i < aggregate; ++i) {
					byte[] payload = createPayload(message, useTimeStamp, useNumberSeq, type_value,format, charset);
					Thread.sleep(throttle);
					IMqttDeliveryToken token = this.client.publish(topic,payload,quality,retained);
					OutMsgsMap.put(i, topic+"#"+message);
					SentMsgsAcksMap.put(token.getMessageId(), false);
				}
			} 						
		} catch (Exception e) {
			e.printStackTrace();
			getLogger().warn(e.getLocalizedMessage(), e);
		}
		//if we are waiting for acks wait at least acksTimeout msecs more
		if ( quality>0 ) {
			int waited=0;
			do {
				Thread.sleep(throttle);
				waited++;
			} while ((getNumMsgsDelivered() < getNumMsgsSent()) && ((waited*throttle) < acksTimeout) );
		}
		//if ((numMsgsDelivered.get() < numMsgsSent.get() )) {
		//	System.out.println( myname + ":" + numMsgsSent.get() + " " + numMsgsDelivered.get() );
		//}
		reconnectOnConnLost = false;
		client.disconnect();
		//client.close();
	}
	
	public byte[] createPayload(String message, String useTimeStamp, String useNumSeq ,String type_value, String format, String charset) throws IOException, NumberFormatException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(b);
// flags  	
    	byte flags=0x00;
		if("TRUE".equals(useTimeStamp)) flags|=0x80;
		if("TRUE".equals(useNumSeq)) flags|=0x40;
		if (MQTTPublisherGui.INT.equals(type_value)) flags|=0x20;
		if (MQTTPublisherGui.LONG.equals(type_value)) flags|=0x10;
		if (MQTTPublisherGui.FLOAT.equals(type_value)) flags|=0x08;
		if (MQTTPublisherGui.DOUBLE.equals(type_value)) flags|=0x04;
		if (MQTTPublisherGui.STRING.equals(type_value)) flags|=0x02;
		if(!"TEXT".equals(type_value)){
			d.writeByte(flags); 
		}		
// TimeStamp
		if("TRUE".equals(useTimeStamp)){
   		 Date date= new java.util.Date();
    	 d.writeLong(date.getTime());
    	                               }
// Number Sequence
		if("TRUE".equals(useNumSeq)){
   	     d.writeInt(numSeq++);   	
   	    
  	    }
// Value				
  	    if (MQTTPublisherGui.INT.equals(type_value)) {
  			d.writeInt(Integer.parseInt(message));  			
  		} else if (MQTTPublisherGui.LONG.equals(type_value)) {
  			d.writeLong(Long.parseLong(message));  		
  		} else if (MQTTPublisherGui.DOUBLE.equals(type_value)) {
  			d.writeDouble(Double.parseDouble(message));  		
  		} else if (MQTTPublisherGui.FLOAT.equals(type_value)) {
  			d.writeDouble(Float.parseFloat(message));  			
  		} else if (MQTTPublisherGui.STRING.equals(type_value)) {
  			d.write(message.getBytes());  			
  		} else if ("TEXT".equals(type_value)) {
  			d.write(message.getBytes());
  		} else if ("TEXT_POOL".equals(type_value)) {
  			String random_message = createRandomMessageFromPool( message );
			d.write(random_message.getBytes());
		}
  	      
// Format: Encoding  	   
  	   if(MQTTPublisherGui.BINARY.equals(format)){
  		   BinaryCodec encoder= new BinaryCodec();
  		   return encoder.encode(b.toByteArray());
  	   } else if(MQTTPublisherGui.BASE64.equals(format)){
  		   return Base64.encodeBase64(b.toByteArray());
  	   } else if(MQTTPublisherGui.BINHEX.equals(format)){
  		   Hex encoder= new Hex();
  		   return encoder.encode(b.toByteArray());
  	   } else if(MQTTPublisherGui.PLAIN_TEXT.equals(format)){  		  
  		   String s= new String (b.toByteArray(),charset);
  		   return s.getBytes();
  		   
  	   } else return b.toByteArray();
	}
       
	/**
	 * 
	 * @param pool: Space separated words composing a pool of strings.
	 * @return
	 */
	public String createRandomMessageFromPool(String pool) {
		String[] ArrayPool = pool.split("\\s+");
		StringBuffer buff = new StringBuffer();
		Random rand = new Random();
		int rlength = rand.nextInt( ArrayPool.length ) + 1;
		for (int i=0; i<rlength; i++) {
			buff.append(ArrayPool[rand.nextInt(ArrayPool.length)]);
			buff.append(" ");
		}
		return buff.toString();
	}
	
	private AtomicInteger heartbeatsNum = new AtomicInteger(0);
	private TimerTask heartbeatTask = new TimerTask () {
		@Override
		public void run() {
			heartbeatsNum.incrementAndGet();
			String heartbeatMsg = "{  \"type\": \"heartbeat\",  \"id\": \"cor-id-#11\" }";
			byte[] payload = null;
			//payload = createPayload(heartbeatMsg, "TRUE", "TRUE", "TEXT", "mqtt_plain_text", "US-ASCII");
			payload = heartbeatMsg.getBytes();
			try {
				IMqttDeliveryToken token = client.publish(heartbeatChannel,payload,0, false, 0, null);
				OutMsgsMap.put(token.getMessageId(), heartbeatChannel + "#" + heartbeatMsg);
			} catch (MqttPersistenceException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (MqttException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		}
	};
	
	private boolean startHeartBeat(Timer t) {
		try {
			client.subscribe(heartbeatChannel, 1);
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		t.scheduleAtFixedRate(heartbeatTask, 0, 1000);
		return true;
		
	}
	
	@Override
	public	void teardownTest(JavaSamplerContext context) {
		System.out.println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
		heartbeatTimer.cancel();
		try {
			client.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void flushMessages() {
		StringBuffer strb = new StringBuffer();
		strb.append("\nSend " + OutMsgsMap.size() + " messages\n");
		for (int key : OutMsgsMap.keySet()) {
			strb.append("\nid: " + key + " : " + OutMsgsMap.get(key));
			strb.append("\n");
		}
		strb.append("\n\nReceived " + InMsgsMap.size() + " messages");
		for (int key : InMsgsMap.keySet()) {
			strb.append("\nid: " + key + " : " + InMsgsMap.get(key));
			strb.append("\n");
		}
		System.out.println(strb.toString());
	}
	
}
