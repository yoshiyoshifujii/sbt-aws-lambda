package com.gilt.aws.lambda

import com.amazonaws.auth.profile.ProfileCredentialsProvider

private[lambda] object AwsCredentials {
  lazy val provider = new ProfileCredentialsProvider()
}
