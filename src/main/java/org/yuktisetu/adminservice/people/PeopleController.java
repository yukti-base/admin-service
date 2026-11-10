package org.yuktisetu.adminservice.people;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.people.PeopleDtos.PeopleFilterOptions;
import org.yuktisetu.adminservice.people.PeopleDtos.PeoplePage;
import org.yuktisetu.adminservice.people.PeopleDtos.PersonDetail;
import org.yuktisetu.adminservice.people.PeopleDtos.StaffRow;
import org.yuktisetu.adminservice.people.PeopleDtos.StaffSearchCriteria;
import org.yuktisetu.adminservice.people.PeopleDtos.StudentRow;
import org.yuktisetu.adminservice.people.PeopleDtos.StudentSearchCriteria;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;

/**
 * People search for the admin portal.
 *
 * The @PreAuthorize gate is coarse: it admits every role that can see anyone.
 * PeopleVisibility does the real scoping (RoleHierarchy.canView within the
 * caller's college / department), and it is enforced in the query itself, so a
 * row outside scope is never loaded rather than loaded and filtered.
 * Ground Volunteers and students see no one and are refused here.
 */
@Slf4j
@RestController
@RequestMapping("/people")
@RequiredArgsConstructor
public class PeopleController {

    private static final String SEARCHERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN',"
                    + "'ROLE_TNP_COORDINATOR','ROLE_HOD','ROLE_FACULTY_DEPT_COORDINATOR')";

    private final PeopleSearchService searchService;

    @GetMapping("/filters")
    @PreAuthorize(SEARCHERS)
    public ResponseEntity<YuktiSetuResponse<PeopleFilterOptions>> filters(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(searchService.filterOptions(actor)));
    }

    /** sort: NAME, EMAIL, CREATED_AT, LAST_LOGIN_AT, PRN, CGPA, BATCH; direction: asc | desc. */
    @GetMapping("/students")
    @PreAuthorize(SEARCHERS)
    public ResponseEntity<YuktiSetuResponse<PeoplePage<StudentRow>>> students(
            @AuthenticationPrincipal UserPrincipal actor,
            @ModelAttribute StudentSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                searchService.searchStudents(actor, criteria, page, size, sort, direction)));
    }

    /** sort: NAME, EMAIL, CREATED_AT, LAST_LOGIN_AT; direction: asc | desc. */
    @GetMapping("/staff")
    @PreAuthorize(SEARCHERS)
    public ResponseEntity<YuktiSetuResponse<PeoplePage<StaffRow>>> staff(
            @AuthenticationPrincipal UserPrincipal actor,
            @ModelAttribute StaffSearchCriteria criteria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                searchService.searchStaff(actor, criteria, page, size, sort, direction)));
    }

    @GetMapping("/{userId}")
    @PreAuthorize(SEARCHERS)
    public ResponseEntity<YuktiSetuResponse<PersonDetail>> detail(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long userId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(searchService.detail(actor, userId)));
    }
}
