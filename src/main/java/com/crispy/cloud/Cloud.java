package com.crispy.cloud;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletResponse;

import com.crispy.net.Get;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.util.StringInputStream;
import com.crispy.log.Log;

/**
 * Cloud
 * 
 * A single happy java class to deal with EC2.
 * 
 * @author harsh
 * 
 */
public class Cloud {
	/*
	 * Common Stuff.
	 */
	private static final Log LOG = Log.get("cloud");

	public static boolean localMode = false;
	private static AWSCredentials credentials;

	public static void init(String credentialsFile) throws Exception {
		Properties props = new Properties();
		props.load(new FileReader(credentialsFile));
		credentials = new BasicAWSCredentials(props.getProperty("accessKey"), 
				props.getProperty("secretKey"));
		System.setProperty("com.amazonaws.sdk.disableCertChecking", "true");
	}

	private static String defaultSecurityGroup = null;
	private static String defaultKeyPair = null;

	private Set<String> keys;
	private boolean neverExpire;

	public static void securityGroup(String sg) {
		defaultSecurityGroup = sg;
	}

	public static void keyPair(String kp) {
		defaultKeyPair = kp;
	}

	public static AWSCredentials getCredentials() {
		return credentials;
	}

	private AmazonS3Client s3;
	private String bucket;
	private AccessControlList acl;

	private InstanceType instanceType;

	private Cloud() {
	}

	public static Cloud s3(String bucket) {
		Cloud c = new Cloud();
		c.s3 = new AmazonS3Client(credentials);
		c.bucket = bucket;
		return c;
	}

	/*
	 * EC2 Stuff
	 */

	private AmazonEC2 ec2;
	private String ami;
	private String userData;
	private String securityGroup;
	private String keyPair;
	private String instanceId;

	public static Cloud ec2() {
		Cloud c = new Cloud();
		c.ec2 = new AmazonEC2Client(credentials);
		c.securityGroup = defaultSecurityGroup;
		c.keyPair = defaultKeyPair;
		return c;
	}

	public Cloud create() {
		if (!s3.doesBucketExist(bucket)) {
			s3.createBucket(bucket);
		}
		return this;
	}

	public Cloud create(String region) {
		if (!s3.doesBucketExist(bucket)) {
			s3.createBucket(bucket, region);
		}
		return this;
	}

	public Cloud allowRead() {
		if (acl == null) {
			acl = new AccessControlList();
		}
		acl.grantPermission(GroupGrantee.AllUsers, Permission.Read);
		return this;
	}

	public Cloud neverExpire() {
		neverExpire = true;
		return this;
	}

	public Cloud ami(String ami) {
		this.ami = ami;
		return this;
	}

	public Cloud userData(String ud) {
		this.userData = ud;
		return this;
	}

	public Cloud type(InstanceType it) {
		this.instanceType = it;
		return this;
	}

	public Cloud instance(String id) {
		this.instanceId = id;
		return this;
	}

	public void terminate() {
		TerminateInstancesRequest tir = new TerminateInstancesRequest();
		tir.withInstanceIds(instanceId);
		ec2.terminateInstances(tir);
		ec2.shutdown();
	}

	public String launch() {
		RunInstancesRequest rir = new RunInstancesRequest();
		rir.withImageId(ami).withSecurityGroups(securityGroup).withKeyName(keyPair).withInstanceType(instanceType).withMinCount(1).withMaxCount(1)
				.withUserData(new String(Base64.encodeBase64(userData.getBytes())));
		RunInstancesResult resp = ec2.runInstances(rir);
		ec2.shutdown();
		return resp.getReservation().getInstances().get(0).getInstanceId();
	}

	public String[] launch(int min, int max) {
		RunInstancesRequest rir = new RunInstancesRequest();
		rir.withImageId(ami).withSecurityGroups(securityGroup).withKeyName(keyPair).withInstanceType(instanceType).withMinCount(min)
				.withMaxCount(max);
		if (userData != null) {
			rir.withUserData(new String(Base64.encodeBase64(userData.getBytes())));
		}
		RunInstancesResult resp = ec2.runInstances(rir);
		String[] ret = new String[resp.getReservation().getInstances().size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = resp.getReservation().getInstances().get(i).getInstanceId();
		}
		ec2.shutdown();
		return ret;
	}

	public Cloud cacheKeys() {
		keys = new TreeSet<String>();
		ObjectListing listing = s3.listObjects(new ListObjectsRequest().withBucketName(bucket).withMaxKeys(Integer.MAX_VALUE));
		for (S3ObjectSummary summary : listing.getObjectSummaries()) {
			keys.add(summary.getKey());
		}
		LOG.info("Loaded " + keys.size() + " keys");
		return this;
	}

	public boolean exists(String key) {
		return keys.contains(key);
	}

	public Set<String> keys() {
		if (keys == null) {
			cacheKeys();
		}
		return keys;
	}

	public String download(String key) {
		try {
			GetObjectRequest request = new GetObjectRequest(bucket, key);
			S3Object o = s3.getObject(request);
			if (o == null)
				return null;
			S3ObjectInputStream is = o.getObjectContent();
			String ret = IOUtils.toString(is);
			is.close();
			return ret;
		} catch (Throwable e) {
			throw new IllegalArgumentException("Error downloading key = " + key, e);
		} finally {
			s3.shutdown();
		}
	}
	
	public void download(String key, HttpServletResponse resp) {
		try {
			GetObjectRequest request = new GetObjectRequest(bucket, key);
			S3Object o = s3.getObject(request);
			if (o == null)
				return;
			S3ObjectInputStream is = o.getObjectContent();
			resp.setContentType(o.getObjectMetadata().getContentType());
			IOUtils.copy(is, resp.getOutputStream());
			resp.getOutputStream().flush();
			is.close();
		} catch (Throwable e) {
			throw new IllegalArgumentException("Error downloading key = " + key, e);
		} finally {
			s3.shutdown();
		}
	}
	
	public void download(String key, File local) {
		try {
			GetObjectRequest request = new GetObjectRequest(bucket, key);
			S3Object o = s3.getObject(request);
			if (o == null)
				return;
			S3ObjectInputStream is = o.getObjectContent();
			FileOutputStream fout = new FileOutputStream(local);
			IOUtils.copy(is, fout);
			fout.flush();
			fout.close();
			is.close();
		} catch (Throwable e) {
			throw new IllegalArgumentException("Error downloading key = " + key, e);
		} finally {
			s3.shutdown();
		}
	}
	

	public void remove(String key) {
		DeleteObjectRequest dor = new DeleteObjectRequest(bucket, key);
		s3.deleteObject(dor);
		s3.shutdown();
	}

	public Cloud upload(String key, String data) throws UnsupportedEncodingException {
		PutObjectRequest request = new PutObjectRequest(bucket, key, new StringInputStream(data), new ObjectMetadata());
		s3.putObject(request);
		return this;
	}

	public List<String> list(String key) {
		try {
			ListObjectsRequest request = new ListObjectsRequest().withBucketName(bucket).withPrefix(key);
			List<String> ret = new ArrayList<>();
			ObjectListing listing;
			do {
				listing = s3.listObjects(request);
				for (S3ObjectSummary summary : listing.getObjectSummaries()) {
					ret.add(summary.getKey());
				}
				request.setMarker(listing.getNextMarker());
			} while (listing.isTruncated());
			return ret;
		} finally {
			s3.shutdown();
		}
	}

	public Cloud upload(String key, File value) throws FileNotFoundException {
		ObjectMetadata metadata = new ObjectMetadata();
		if (value.getName().endsWith("png")) {
			metadata.setContentType("image/png");
		} else if (value.getName().endsWith("jpg") || value.getName().endsWith("jpeg")) {
			metadata.setContentType("image/jpg");
		}
		if (neverExpire) {
			metadata.setCacheControl("max-age=8640000");
		}

		PutObjectRequest request = new PutObjectRequest(bucket, key, value);
		request.setMetadata(metadata);
		if (acl != null) {
			request = request.withAccessControlList(acl);
		}
		s3.putObject(request);
		return this;
	}

	public Cloud upload(String key, URI url) throws ClientProtocolException, IOException {
		DefaultHttpClient client = new DefaultHttpClient();
		HttpGet get = new HttpGet(url);
		HttpResponse response = client.execute(get);
		if (response.getStatusLine().getStatusCode() == 200) {
			HttpEntity entity = response.getEntity();
			ObjectMetadata metadata = new ObjectMetadata();
			if (url.getPath().endsWith("png")) {
				metadata.setContentType("image/png");
			} else if (url.getPath().endsWith("jpg")) {
				metadata.setContentType("image/jpg");
			}
			if (neverExpire) {
				metadata.setCacheControl("max-age=8640000");
			}
			PutObjectRequest request = new PutObjectRequest(bucket, key, entity.getContent(), metadata);
			if (acl != null) {
				request = request.withAccessControlList(acl);
			}
			s3.putObject(request);
			EntityUtils.consume(entity);
		} else {
			EntityUtils.consume(response.getEntity());
		}
		return this;
	}

	public static String userData() {
		try {
			return Get.create().withUrl("http://169.254.169.254/latest/user-data").withTimeout(5).response();
		} catch (Exception e) {
			LOG.warn(e.getMessage(), e);
			return null;
		}
	}

	public static String instanceId() {
		try {
			return Get.create().withUrl("http://169.254.169.254/latest/meta-data/instance-id").withTimeout(5)
					.response();
		} catch (Exception e) {
			LOG.warn(e.getMessage(), e);
			return "local";
		}
	}

	public void shutdown() {
		if (ec2 != null)
			ec2.shutdown();
		if (s3 != null)
			s3.shutdown();
	}

	public boolean isTerminated(String instanceId) {
		DescribeInstancesRequest dir = new DescribeInstancesRequest();
		dir.withInstanceIds(instanceId);
		DescribeInstancesResult res = ec2.describeInstances(dir);
		InstanceState state = res.getReservations().get(0).getInstances().get(0).getState();
		return state.getName().equals("terminated");
	}
}
