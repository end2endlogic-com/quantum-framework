package com.e2eq.framework.model.persistent.morphia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.e2eq.framework.model.persistent.collaboration.Comment;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class CommentRepoHierarchyTest {

    @Test
    void replyDepthIsDerivedFromParent() {
        ObjectId chainId = new ObjectId();
        Comment parent = Comment.builder().chainId(chainId).depth(4).build();

        assertEquals(5, CommentRepo.depthForParent(chainId, parent));
    }

    @Test
    void parentMustBelongToSameChain() {
        Comment parent = Comment.builder().chainId(new ObjectId()).depth(0).build();

        CommentHierarchyException failure = assertThrows(
                CommentHierarchyException.class,
                () -> CommentRepo.depthForParent(new ObjectId(), parent));

        assertEquals(CommentHierarchyException.Code.PARENT_CHAIN_MISMATCH, failure.getCode());
    }

    @Test
    void replyDepthIsBounded() {
        ObjectId chainId = new ObjectId();
        Comment parent = Comment.builder().chainId(chainId).depth(Comment.MAX_DEPTH).build();

        CommentHierarchyException failure = assertThrows(
                CommentHierarchyException.class,
                () -> CommentRepo.depthForParent(chainId, parent));

        assertEquals(CommentHierarchyException.Code.MAX_DEPTH_EXCEEDED, failure.getCode());
    }
}
