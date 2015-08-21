package com.gilt.aws.lambda

import java.net.URLEncoder

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
      val policyDocument =
        """
          |{
          |  "Version": "2012-10-17",
          |  "Statement": [
          |    {
          |      "Effect": "Allow",
          |      "Action": [
          |        "logs:CreateLogGroup",
          |        "logs:CreateLogStream",
          |        "logs:PutLogEvents"
          |      ],
          |      "Resource": "arn:aws:logs:*:*:*"
          |    }
          |  ]
          |}
        """.stripMargin

      val c = new CreateRoleRequest
      c.setRoleName(BasicLambdaRoleName)
      c.setAssumeRolePolicyDocument(URLEncoder.encode(policyDocument, "UTF-8"))
      c
    }

    val result = iamClient.createRole(createRoleRequest)
    RoleARN(result.getRole.getArn)
  }
}
