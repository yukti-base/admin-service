package org.yuktisetu.adminservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ResendInvitesResponse {
    private int sentCount;
    /** Ids that were not re-invited: unknown, deleted, or already activated. */
    private List<Long> skippedUserIds;
}
