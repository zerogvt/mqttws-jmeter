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
import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.util.Debug;


public class MqttSubscriber extends AbstractJavaSamplerClient implements Serializable, MqttCallback {
	private static final long serialVersionUID = 1L;
	private MqttAsyncClient client;
	//private Debug clientDebug;
	private List<String> allmessages =  new ArrayList<String>();
	private AtomicInteger nummsgs = new AtomicInteger(0);
	private long msgs_aggregate = Long.MAX_VALUE;
	private long samplerTimeout = 30000;
	private long connectionTimeout = 10000;
	private String host ;
	private String clientId ;
	private String myname = this.getClass().getName();
	private MqttConnectOptions options = new MqttConnectOptions();
	private boolean reconnectOnConnLost = true;
	
	
	//common amongst objects
	private static final Logger log = LoggingManager.getLoggerForClass();

	@Override
	public Arguments getDefaultParameters() {
		Arguments defaultParameters = new Arguments();
		defaultParameters.addArgument("HOST", "tcp://localhost:1883");
		defaultParameters.addArgument("CLIENT_ID", "${__time(YMDHMS)}${__threadNum}");
		defaultParameters.addArgument("TOPIC", "TEST.MQTT");
		defaultParameters.addArgument("AGGREGATE", "100");
		defaultParameters.addArgument("CLEAN_SESSION", "false");
		return defaultParameters;
	}

	public void setupTest(JavaSamplerContext context){
		//do nothing yet
	}
	
	public void delayedSetup(JavaSamplerContext context){
		myname = context.getParameter("SAMPLER_NAME");
		host = context.getParameter("HOST");
		clientId = context.getParameter("CLIENT_ID");
		
		if("TRUE".equalsIgnoreCase(context.getParameter("RANDOM_SUFFIX"))){
			clientId= MqttPublisher.getClientId(clientId,Integer.parseInt(context.getParameter("SUFFIX_LENGTH")));	
		}
		try {
			log.debug(myname + ": Host: " + host + "clientID: " + clientId);
			client = new MqttAsyncClient(host, clientId, new MemoryPersistence());
			//clientDebug = client.getDebug();
		} catch (MqttException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		if ( !context.getParameter("AGGREGATE").equals("")) {
			msgs_aggregate = Long.parseLong(context.getParameter("AGGREGATE"));	
		}
		if ( !context.getParameter("SAMPLER_TIMEOUT").equals("") ) {
			samplerTimeout = Long.parseLong(context.getParameter("SAMPLER_TIMEOUT"));
		}
		
		//System.out.println("nummsgs: " + msgs_aggregate + " - sampler timeout: " + samplerTimeout);
		//options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(Boolean.parseBoolean((context.getParameter("CLEAN_SESSION"))));
		//System.out.println("Subs clean session====> " + context.getParameter("CLEAN_SESSION"));
		options.setKeepAliveInterval(30);
		connectionTimeout = Integer.parseInt((context.getParameter("CONNECTION_TIMEOUT")));
		String user = context.getParameter("USER"); 
		String pwd = context.getParameter("PASSWORD");
		if (user != null) {
			options.setUserName(user);
			if ( pwd!=null ) {
				options.setPassword(pwd.toCharArray());
			}
		}
		//System.out.println("============> " + options.getKeepAliveInterval());
		
		clientConnect();
		
		client.setCallback(this);

	}

	private boolean clientConnect(){
		log.info("Subscriber connecting...(conn timeout: " + connectionTimeout + ")");
		if (client.isConnected()) {
			return true;
		}
		try {
			IMqttToken token = client.connect(options);
			token.waitForCompletion(connectionTimeout);
		} catch (MqttSecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		finally {
			if (!client.isConnected()) {
				//log.info("##Dumping client info (failed initial connection): ");
				//clientDebug.dumpClientDebug();
			}
		}
		return client.isConnected();
	}
	
	private class EndTask extends TimerTask  {
		boolean timeup = false;
	    public void run()  {
	      log.debug(myname + ": Time's up! " + new Date().toString());
	      //System.out.println(myname + ": Time's up! " + new Date().toString());
	      timeup = true;
	      }
	    public boolean isTimeUp(){
	    	return timeup;
	    }
	 }

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		delayedSetup(context);
		log.debug(myname + " >>>> in runtest");
		SampleResult result = new SampleResult();
		result.setSampleLabel(context.getParameter("SAMPLER_NAME"));
		//be optimistic - will set an error if we find one
		result.setResponseOK();
		
		if (!client.isConnected() ) {
			log.error(myname + " >>>> Client is not connected - Returning false");
			result.setResponseMessage("Cannot connect to broker: " + client.getServerURI());
			result.setResponseCode("FAILED");
			result.setSuccessful(false);
			result.setSamplerData("ERROR: Could not connect to broker: " + client.getServerURI());
			return result;
		}
		result.sampleStart(); // start stopwatch
		
		try {
			//System.out.println(myname + ": Subscribing to topic: " + context.getParameter("TOPIC"));
			log.info(myname + ": Subscribing to topic: " + context.getParameter("TOPIC"));
			client.subscribe(context.getParameter("TOPIC"), 0);
		} catch (MqttException e) {
			log.error(myname + ": Client not connected - Aborting test");
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		EndTask endtask = new EndTask();
		Timer timer = new Timer();
		timer.schedule( endtask, samplerTimeout);
		log.info("starting listening: " + new Date().toString() + "(timeout= " + samplerTimeout + ")");
		while ( !endtask.isTimeUp() ) {
			if (nummsgs.get()<msgs_aggregate) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				log.info(myname + ": All messages received. Disconnecting client and ending test");
				break;
			}
		};
		log.info(myname + ": Timeout. Stopping listening. Heard " + nummsgs.get() + " so far.");
		//test is over - disconnect client
		try {
			reconnectOnConnLost = false;
			client.disconnect();
			//client.close();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		result.sampleEnd(); 
		try {
			StringBuilder allmsgs = new StringBuilder();
			if ( !allmessages.isEmpty() ) {
				for (String s : this.allmessages)
				{
				  allmsgs.append(s + "\n");
				}
				result.setResponseMessage("Received " + allmessages.size() + " messages: \n" + allmsgs.toString() );
				result.setResponseData(allmsgs.toString(),null);
			} else {
				result.setResponseMessage("No messages received from broker: " + host);
				result.setResponseCode("FAILED");
				result.setSuccessful(false);
			}
			if ( msgs_aggregate != Long.MAX_VALUE) {
				if ( nummsgs.get() >= msgs_aggregate ) {
					result.setResponseOK();
				}
				else {
					result.setResponseMessage("Fewer than anticipated messages received from broker: " + host);
					result.setResponseCode("FAILED");
					result.setSuccessful(false);
				}
			} else {
				if (nummsgs.get()!=0) {
					result.setResponseOK();
				}
			}
			result.setSamplerData("Listened " + nummsgs.get() + " messages" +
			"\nTopic: " + context.getParameter("TOPIC") + 
			"\nBroker: " + host +
			"\nMy client ID: " + clientId);
			
		} catch (Exception e) {
			result.sampleEnd(); // stop stopwatch
			result.setResponseMessage("Exception: " + e);
			// get stack trace as a String to return as document data
			java.io.StringWriter stringWriter = new java.io.StringWriter();
			e.printStackTrace( new java.io.PrintWriter(stringWriter) );
			result.setResponseData(stringWriter.toString(), null);
			result.setDataType(org.apache.jmeter.samplers.SampleResult.TEXT);
			result.setResponseCode("FAILED");
			result.setSuccessful(false);
		}
	
		log.debug(myname + " ending runTest");
		
		return result;
	}


	public void close(JavaSamplerContext context) {
		
		
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
			log.warn(myname + "WARNING: Subscriber client connection was lost.  Reason: "+ arg0.getMessage() + ". Will try reconnection.");
			//System.out.println("WARNING: Subscriber client connection was lost.  Reason: "+ arg0.getMessage() + ". Will try reconnection.");
			//log.info("#Dumping client debug: ");
			//clientDebug.dumpClientDebug();
			clientConnect();
			connecting=false;
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void messageArrived(String str, MqttMessage msg) throws Exception {
		nummsgs.incrementAndGet();
		log.debug(myname + ": num msgs: " + nummsgs.get() +  ". Got message: " + new String(msg.getPayload()));
		// TODO Auto-generated method stub
		allmessages.add(new String(msg.getPayload()));
		
	}
	
}
