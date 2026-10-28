package org.yuktisetu.adminservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/** A student who was imported but has not accepted their invite yet. */
@Data
@Builder
public class PendingInviteDto {
    private Long userId;
    private String firstName;
    private String lastName;
    private String email;
    private Date createdAt;
}
