package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.collaboration.Comment;
import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.mongodb.MongoWriteException;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.types.ObjectId;

@ApplicationScoped
public class CommentRepo extends MorphiaRepo<Comment> {

    @Inject
    CommentChainRepo commentChainRepo;

    @Inject
    MediaReferenceRepo mediaReferenceRepo;

    @Override
    public Comment save(@Valid Comment value) {
        if (value.getChainId() == null) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.CHAIN_REQUIRED,
                    "A comment chain id is required");
        }
        if (commentChainRepo.findById(value.getChainId()).isEmpty()) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.CHAIN_NOT_FOUND,
                    "Comment chain not found: " + value.getChainId());
        }

        Comment existing = value.getId() == null ? null : findById(value.getId()).orElse(null);
        String requestId = clean(value.getRequestId());
        value.setRequestId(requestId);
        if (existing == null && requestId != null) {
            Optional<Comment> prior = findByRequestId(value.getChainId(), requestId);
            if (prior.isPresent()) {
                return prior.get();
            }
        }
        if (existing != null
                && (!Objects.equals(existing.getChainId(), value.getChainId())
                    || !Objects.equals(existing.getParentCommentId(), value.getParentCommentId()))) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.IMMUTABLE_HIERARCHY,
                    "A persisted comment cannot be moved to another chain or parent");
        }
        if (existing != null
                && !Objects.equals(actorId(existing), actorId(value))) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.IMMUTABLE_AUTHOR,
                    "A persisted comment author cannot be changed");
        }
        if (existing != null
                && !Objects.equals(existing.getRequestId(), requestId)) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.IMMUTABLE_REQUEST_ID,
                    "A persisted comment request id cannot be changed");
        }

        if (existing == null) {
            value.setCreatedAt(Instant.now());
            value.setDepth(resolveDepth(value.getChainId(), value.getParentCommentId()));
        } else {
            value.setCreatedAt(existing.getCreatedAt());
            value.setDepth(existing.getDepth());
        }
        validateMediaReferences(value.getMediaReferences());
        try {
            return super.save(value);
        } catch (MongoWriteException failure) {
            if (existing == null && requestId != null && failure.getError().getCode() == 11000) {
                return findByRequestId(value.getChainId(), requestId).orElseThrow(() -> failure);
            }
            throw failure;
        }
    }

    private static String actorId(Comment comment) {
        return comment.getAuthor() == null ? null : comment.getAuthor().getActorId();
    }

    private void validateMediaReferences(List<EntityReference> references) {
        if (references == null) {
            return;
        }
        for (EntityReference reference : references) {
            if (reference == null
                    || (reference.getEntityId() == null
                        && (reference.getEntityRefName() == null
                            || reference.getEntityRefName().isBlank()))) {
                throw new CommentHierarchyException(
                        CommentHierarchyException.Code.MEDIA_REFERENCE_INVALID,
                        "Each comment attachment must identify a MediaReference");
            }
            String entityType = reference.getEntityType();
            if (entityType != null
                    && !entityType.isBlank()
                    && !entityType.equals(MediaReference.class.getSimpleName())
                    && !entityType.equals(MediaReference.class.getName())) {
                throw new CommentHierarchyException(
                        CommentHierarchyException.Code.MEDIA_REFERENCE_INVALID,
                        "Comment attachments must reference MediaReference records");
            }
            boolean found = reference.getEntityId() != null
                    ? mediaReferenceRepo.findById(reference.getEntityId()).isPresent()
                    : mediaReferenceRepo.findByRefName(reference.getEntityRefName()).isPresent();
            if (!found) {
                throw new CommentHierarchyException(
                        CommentHierarchyException.Code.MEDIA_REFERENCE_NOT_FOUND,
                        "MediaReference is not visible in the current governed context");
            }
        }
    }

    public List<Comment> findByChain(ObjectId chainId, int limit) {
        return findGoverned(
                List.of(Filters.eq("chainId", chainId)),
                Math.max(1, Math.min(limit, 1000)));
    }

    public List<Comment> findReplies(ObjectId chainId, ObjectId parentCommentId, int limit) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("chainId", chainId));
        filters.add(Filters.eq("parentCommentId", parentCommentId));
        return findGoverned(filters, Math.max(1, Math.min(limit, 1000)));
    }

    public Optional<Comment> findByRequestId(ObjectId chainId, String requestId) {
        String cleanRequestId = clean(requestId);
        if (chainId == null || cleanRequestId == null) {
            return Optional.empty();
        }
        return findGoverned(
                List.of(
                        Filters.eq("chainId", chainId),
                        Filters.eq("requestId", cleanRequestId)),
                1).stream().findFirst();
    }

    int resolveDepth(ObjectId chainId, ObjectId parentCommentId) {
        if (parentCommentId == null) {
            return 0;
        }
        Comment parent = findById(parentCommentId).orElseThrow(() ->
                new CommentHierarchyException(
                        CommentHierarchyException.Code.PARENT_NOT_FOUND,
                        "Parent comment not found: " + parentCommentId));
        return depthForParent(chainId, parent);
    }

    static int depthForParent(ObjectId chainId, Comment parent) {
        if (!Objects.equals(chainId, parent.getChainId())) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.PARENT_CHAIN_MISMATCH,
                    "Parent comment belongs to a different chain");
        }
        int depth = parent.getDepth() + 1;
        if (depth > Comment.MAX_DEPTH) {
            throw new CommentHierarchyException(
                    CommentHierarchyException.Code.MAX_DEPTH_EXCEEDED,
                    "Comment hierarchy exceeds maximum depth " + Comment.MAX_DEPTH);
        }
        return depth;
    }

    private List<Comment> findGoverned(List<Filter> requestedFilters, int limit) {
        Filter[] governed = getFilterArray(new ArrayList<>(requestedFilters), getPersistentClass());
        FindOptions options = new FindOptions()
                .sort(Sort.ascending("createdAt"), Sort.ascending("_id"))
                .limit(limit);
        return getMorphiaDataStore()
                .find(Comment.class)
                .filter(governed)
                .iterator(options)
                .toList();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}
