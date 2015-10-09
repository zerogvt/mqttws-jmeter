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
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import org.eclipse.paho.client.mqttv3.MqttAsyncClient;

//import io.inventit.dev.mqtt.paho.MqttWebSocketAsyncClient;

public class MqttSubscriber extends AbstractJavaSamplerClient implements Serializable, MqttCallback {
	private static final long serialVersionUID = 1L;
	//private MqttWebSocketAsyncClient client;
	private MqttAsyncClient client;
	private List<String> allmessages =  new ArrayList<String>();
	private AtomicInteger nummsgs = new AtomicInteger(0);
	
	static long msgs_aggregate = Long.MAX_VALUE;
	static long timeout = 10000;
	
	static String host ;
	static String clientId ;
	
	String myname = this.getClass().getName();


	@Override
	public Arguments getDefaultParameters() {
		Arguments defaultParameters = new Arguments();
		defaultParameters.addArgument("HOST", "tcp://localhost:1883");
		defaultParameters.addArgument("CLIENT_ID", "${__time(YMDHMS)}${__threadNum}");
		defaultParameters.addArgument("TOPIC", "TEST.MQTT");
		defaultParameters.addArgument("AGGREGATE", "100");
		defaultParameters.addArgument("DURABLE", "false");
		return defaultParameters;
	}

	public void setupTest(JavaSamplerContext context){
		//do nothing yet
	}
	
	public void delayedSetup(JavaSamplerContext context){
		//System.out.println(myname + ">>>> in setupTest");
		host = context.getParameter("HOST");
		clientId = context.getParameter("CLIENT_ID");
		
		if("TRUE".equalsIgnoreCase(context.getParameter("RANDOM_SUFFIX"))){
			clientId= MqttPublisher.getClientId(clientId,Integer.parseInt(context.getParameter("SUFFIX_LENGTH")));	
		}
		try {
			System.out.println("Host: " + host + "clientID: " + clientId);
			//client = new MqttWebSocketAsyncClient(host, clientId, new MemoryPersistence());
			client = new MqttAsyncClient(host, clientId, new MemoryPersistence());
			//client = new MqttClient(host, clientId, new MemoryPersistence());
		} catch (MqttException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		/*
		//wait out for messages till TIMEOUT expires or aggregate msgs count if set 
		if (!context.getParameter("AGGREGATE").trim().equals("")) {
			try {
				msgs_aggregate = Long.parseLong(context.getParameter("AGGREGATE"));
			} catch (NumberFormatException e) {
				msgs_aggregate = Long.MAX_VALUE;
			}
		}
		*/
		if ( !context.getParameter("AGGREGATE").equals("")) {
			msgs_aggregate = Long.parseLong(context.getParameter("AGGREGATE"));	
		}
		if ( !context.getParameter("TIMEOUT").equals("") ) {
			timeout = Long.parseLong(context.getParameter("TIMEOUT"));
		}
		
		System.out.println("nummsgs: " + msgs_aggregate + " - timeout: " + timeout);
		
		MqttConnectOptions options = new MqttConnectOptions();
		//options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(true);
		/*String user = context.getParameter("USER"); 
		String pwd = context.getParameter("PASSWORD");
		boolean durable = Boolean.parseBoolean(context.getParameter("DURABLE"));
		options.setCleanSession(!durable);
		if (user != null) {
			options.setUserName(user);
			if ( pwd!=null ) {
				options.setPassword(pwd.toCharArray());
			}
		}
		*/
		//TODO more options here
		try {
			client.connect(options);
			int i=0;
			if (!client.isConnected() && (i<10) ) {
				try {
					i++;
					Thread.sleep(1000);
					System.out.println(".");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
		} catch (MqttSecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		client.setCallback(this);

	}

	
	private class EndTask extends TimerTask  {
		boolean timeup = false;
	    public void run()  {
	      System.out.println("Time's up!");
	      timeup = true;
	      }
	    public boolean isTimeUp(){
	    	return timeup;
	    }
	 }

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		delayedSetup(context);
		//System.out.println(myname + " >>>> in runtest");
		SampleResult result = new SampleResult();
		result.setSampleLabel(context.getParameter("SAMPLER_NAME"));
		
		
		if (!client.isConnected() ) {
			System.out.println(myname + " >>>> Client is not connected - Returning false");
			result.setSuccessful(false);
			result.setResponseMessage("Cannot connect to broker");
			result.setResponseCode("FAILED");
			result.setSamplerData("ERROR: Could not connect to broker: " + client.getServerURI());
			return result;
		}
		result.sampleStart(); // start stopwatch
		try {
			System.out.println("Subscribing to topic: " + context.getParameter("TOPIC"));
			client.subscribe(context.getParameter("TOPIC"), 0);
		} catch (MqttException e) {
			System.out.println("Client not connected - Aborting test");
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		EndTask endtask = new EndTask();
		Timer timer = new Timer();
		timer.schedule( endtask, timeout);
		while ( !endtask.isTimeUp() ) {
			if (nummsgs.get()<msgs_aggregate) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				System.out.println("All messages received. Disconnecting client and ending test");
				break;
			}
		};
		System.out.println("Timeout. Stopping listening. Heard " + nummsgs.get() + " so far.");
		//test is over - disconnect client
		try {
			client.disconnect();
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
				result.setResponseMessage("No messages received");
				result.setResponseCode("FAILED");
			}
			if ( msgs_aggregate != Long.MAX_VALUE) {
				if ( nummsgs.get() >= msgs_aggregate ) {
					result.setResponseOK();
				}
				else
					result.setResponseCode("FAILED");
			} else {
				if (nummsgs.get()!=0) {
					result.setResponseOK();
				}
			}
			result.setSamplerData("Listened " + nummsgs.get() + " messages" +
			"\nTopic: " + context.getParameter("TOPIC") + 
			"\nBroker: " + client.getServerURI());
			
		} catch (Exception e) {
			result.sampleEnd(); // stop stopwatch
			result.setSuccessful(false);
			result.setResponseMessage("Exception: " + e);
			// get stack trace as a String to return as document data
			java.io.StringWriter stringWriter = new java.io.StringWriter();
			e.printStackTrace( new java.io.PrintWriter(stringWriter) );
			result.setResponseData(stringWriter.toString(), null);
			result.setDataType(org.apache.jmeter.samplers.SampleResult.TEXT);
			result.setResponseCode("FAILED");
		}
	
		System.out.println("ending runTest");
		
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

	@Override
	public void connectionLost(Throwable arg0) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void messageArrived(String str, MqttMessage msg) throws Exception {
		nummsgs.incrementAndGet();
		System.out.println(nummsgs.get() +  " got message: " + new String(msg.getPayload()));
		// TODO Auto-generated method stub
		allmessages.add(new String(msg.getPayload()));
	}
	//test
}
