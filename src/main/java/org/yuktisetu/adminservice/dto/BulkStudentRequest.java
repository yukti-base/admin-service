package org.yuktisetu.adminservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class BulkStudentRequest {
    List<StudentRequest> students;
}
