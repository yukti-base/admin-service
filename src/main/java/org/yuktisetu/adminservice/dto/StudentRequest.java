package org.yuktisetu.adminservice.dto;

import lombok.Data;

@Data
public class StudentRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Double CGPA;
    private Double sem1Gpa;
    private Double sem2Gpa;
    private Double sem3Gpa;
    private Double sem4Gpa;
    private Double sem5Gpa;
    private Double sem6Gpa;
    private Double sem7Gpa;
    private Double sem8Gpa;
    private Double percentage10th;
    private Double percentage12th;
    private String prn;
}
