package org.yuktisetu.adminservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class ResendInvitesRequest {
    private List<Long> userIds;
}
