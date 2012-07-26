package com.crispy;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.Grantee;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;

public class Cloud {
	private static AWSCredentials credentials;
	private static ConcurrentHashMap<String, Boolean> buckets;

	public static void init(String accessKey, String secretKey) {
		credentials = new BasicAWSCredentials(accessKey, secretKey);
		buckets = new ConcurrentHashMap<String, Boolean>();
	}

	private AmazonS3 s3;
	private String bucket;
	private AccessControlList acl;

	private Cloud() {

	}

	public static Cloud s3(String bucket) {
		Cloud c = new Cloud();
		c.s3 = new AmazonS3Client(credentials);
		c.bucket = bucket;
		return c;
	}

	public void create() {
		s3.createBucket(bucket);
	}

	public void create(String region) {
		s3.createBucket(bucket, region);
	}

	public Cloud allowRead() {
		if (acl == null) {
			acl = new AccessControlList();
		}
		acl.grantPermission(GroupGrantee.AllUsers, 
				Permission.Read);
		return this;
	}

	public Cloud upload(String key, File value) {
		PutObjectRequest request = new PutObjectRequest(bucket, key, value);
		if (acl != null) {
			request = request.withAccessControlList(acl);
		}
		s3.putObject(request);
		return this;
	}

	public Cloud upload(String key, String url) throws ClientProtocolException, IOException {
		DefaultHttpClient client = new DefaultHttpClient();
		HttpGet get = new HttpGet(url);
		HttpResponse response = client.execute(get);
		if (response.getStatusLine().getStatusCode() == 200) {
			HttpEntity entity = response.getEntity();
			ObjectMetadata metadata = new ObjectMetadata();
			if (url.endsWith("png")) {
				metadata.setContentType("image/png");
			} else if (url.endsWith("jpg")) {
				metadata.setContentType("image/jpg");
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

	public static void main(String[] args) throws Exception {
		Cloud.init("16ZY7NJB3DZYTPK9T6R2", "YMxgM89oOxFBrmmvLET625MX5A4oHfmHQ1Z0R2/Y");
				
		Cloud.s3("crispy-jappy").allowRead()
				.upload("chatpat/full/369.jpg",
						"http://upload.wikimedia.org/wikipedia/en/thumb/5/5d/Cocktail_2012_poster.jpg/220px-Cocktail_2012_poster.jpg");
	}
}
