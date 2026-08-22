package com.e2eq.framework.rest.resources;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CommentChainOpenApiIT {

    @Test
    void publishesGeneratedSdkCompatibleCommentOperations() {
        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("paths.'/comment-chains'.post.operationId", equalTo("createCommentChain"))
                .body("paths.'/comment-chains/{chainId}'.get.operationId", equalTo("getCommentChain"))
                .body(
                        "paths.'/comment-chains/by-external-subject'.get.operationId",
                        equalTo("listCommentChainsByExternalSubject"))
                .body(
                        "paths.'/comment-chains/{chainId}/comments'.post.operationId",
                        equalTo("createComment"))
                .body(
                        "paths.'/comment-chains/{chainId}/comments'.get.operationId",
                        equalTo("listComments"))
                .body(
                        "paths.'/comment-chains/{chainId}/comments/{parentCommentId}/replies'.get.operationId",
                        equalTo("listCommentReplies"))
                .body("components.schemas.CommentCreateRequest", notNullValue())
                .body("components.schemas.CommentCreateRequest.properties.author", nullValue())
                .body("components.schemas.CommentCreateRequest.properties.createdAt", nullValue())
                .body("components.schemas.Comment", notNullValue())
                .body("components.schemas.CommentChain", notNullValue())
                .body("paths.'/media-references/uploads'.post.operationId", equalTo("prepareMediaUpload"))
                .body(
                        "paths.'/media-references/{mediaReferenceId}/upload-completion'.post.operationId",
                        equalTo("completeMediaUpload"))
                .body(
                        "paths.'/media-references/{mediaReferenceId}'.get.operationId",
                        equalTo("getMediaReference"))
                .body(
                        "paths.'/media-references/{mediaReferenceId}/download-grants'.post.operationId",
                        equalTo("prepareMediaDownload"))
                .body("components.schemas.MediaReference", notNullValue());
    }
}
