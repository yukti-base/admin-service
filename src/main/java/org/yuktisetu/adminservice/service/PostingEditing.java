package org.yuktisetu.adminservice.service;

import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.PostingStatusTransitions;

/**
 * When a drive's JD, criteria, targets and policy may be changed.
 *
 * Wider than PostingStatusTransitions.isEditable (DRAFT / PENDING_APPROVAL): a
 * PUBLISHED drive stays editable too, and the TnP applies the changes with
 * Re-publish. Once applications close the pool is sealed and edits stop.
 */
final class PostingEditing {

    private PostingEditing() {}

    static boolean isEditable(PostingStatus status) {
        return PostingStatusTransitions.isEditable(status) || status == PostingStatus.PUBLISHED;
    }
}
