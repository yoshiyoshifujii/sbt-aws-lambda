package com.gilt.aws.lambda

import com.amazonaws.auth._

private[lambda] object AwsCredentials {
  lazy val provider: AWSCredentialsProvider = new DefaultAWSCredentialsProviderChain()
}
