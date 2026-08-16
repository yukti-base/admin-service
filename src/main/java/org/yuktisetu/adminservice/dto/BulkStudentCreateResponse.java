package org.yuktisetu.adminservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BulkStudentCreateResponse {
    private int totalProcessed;
    private int successfulCount;
    private int failedCount;
    private List<BulkStudentSuccessDto> successfulStudents;
    private List<BulkStudentFailureDto> failedStudents;
    
    @Data
    @Builder
    public static class BulkStudentSuccessDto {
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String prn;
        private String message;
    }
    
    @Data
    @Builder
    public static class BulkStudentFailureDto {
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String errorMessage;
        private String failedField;
    }
}
