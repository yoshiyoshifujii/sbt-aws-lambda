package com.gilt.aws.lambda

import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import com.amazonaws.services.identitymanagement.model.{CreateRoleRequest, Role}

private[lambda] object AwsIAM {

  val BasicLambdaRoleName = "lambda_basic_execution"

  lazy val iamClient = new AmazonIdentityManagementClient(AwsCredentials.provider)

  def basicLambdaRole(): Option[Role] = {
    import scala.collection.JavaConverters._
    val existingRoles = iamClient.listRoles().getRoles.asScala

    existingRoles.find(_.getRoleName == BasicLambdaRoleName)
  }

  def createBasicLambdaRole(): RoleARN = {
    val createRoleRequest = {
      val policyDocument = """{"Version":"2012-10-17","Statement":[{"Sid":"","Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}"""
      val c = new CreateRoleRequest
      c.setRoleName(BasicLambdaRoleName)
      c.setAssumeRolePolicyDocument(policyDocument)
      c
    }

    val result = iamClient.createRole(createRoleRequest)
    RoleARN(result.getRole.getArn)
  }
}
