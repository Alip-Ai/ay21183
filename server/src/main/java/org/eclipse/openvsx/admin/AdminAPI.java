/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.openvsx.LocalRegistryService;
import org.eclipse.openvsx.entities.AdminStatistics;
import org.eclipse.openvsx.entities.NamespaceMembership;
import org.eclipse.openvsx.entities.PersistedLog;
import org.eclipse.openvsx.json.*;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.search.SearchUtilService;
import org.eclipse.openvsx.util.*;
import org.springframework.data.util.Streamable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@ApiResponse(
        responseCode = "403",
        description = "Administration role is required",
        content = @Content()
)
public class AdminAPI {

    private final RepositoryService repositories;
    private final AdminService admins;
    private final LocalRegistryService local;
    private final SearchUtilService search;

    public AdminAPI(
            RepositoryService repositories,
            AdminService admins,
            LocalRegistryService local,
            SearchUtilService search
    ) {
        this.repositories = repositories;
        this.admins = admins;
        this.local = local;
        this.search = search;
    }

    @GetMapping(
            path = "/admin/report",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Get the admin report for the given month and year")
    @ApiResponse(
            responseCode = "200",
            description = "The report is returned",
            content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminStatisticsJson.class)),
                    @Content(mediaType = "text/csv", schema = @Schema(type = "string"))
            }
    )
    @ApiResponse(
            responseCode = "400",
            description = "An error message is returned in JSON format",
            content = @Content()
    )
    public ResponseEntity<AdminStatisticsJson> getReportJson(
            @RequestParam("token") @Parameter(description = "A personal access token") String tokenValue,
            @RequestParam("year") int year,
            @RequestParam("month") int month
    ) {
        try {
            var statistics = getReport(tokenValue, year, month);
            return ResponseEntity.ok(statistics.toJson());
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(AdminStatisticsJson.class);
        }
    }

    @GetMapping(
            path = "/admin/report",
            produces = "text/csv"
    )
    @CrossOrigin
    @Operation(hidden = true)
    public ResponseEntity<String> getReportCsv(
            @RequestParam("token") String tokenValue,
            @RequestParam("year") int year,
            @RequestParam("month") int month
    ) {
        try {
            var statistics = getReport(tokenValue, year, month);
            return ResponseEntity.ok(statistics.toCsv());
        } catch (ErrorResultException exc) {
            return ResponseEntity.status(exc.getStatus()).body(exc.getMessage());
        }
    }

    private AdminStatistics getReport(String tokenValue, int year, int month) {
        admins.checkAdminUser(tokenValue);
        return admins.getAdminStatistics(year, month);
    }

    @GetMapping(
        path = "/admin/stats",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<StatsJson> getStats() {
        try {
            admins.checkAdminUser();

            var json = new StatsJson();
            json.setUserCount(repositories.countUsers());
            json.setExtensionCount(repositories.countExtensions());
            json.setNamespaceCount(repositories.countNamespaces());
            return ResponseEntity.ok(json);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(StatsJson.class);
        }
    }

    @GetMapping(
        path = "/admin/log",
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    @Operation(hidden = true)
    public String getLog(@RequestParam(name = "period", required = false) String periodString) {
        try {
            admins.checkAdminUser();

            Streamable<PersistedLog> logs;
            if (StringUtils.isEmpty(periodString)) {
                logs = repositories.findAllPersistedLogs();
            } else {
                try {
                    var period = Period.parse(periodString);
                    var now = TimeUtil.getCurrentUTC();
                    logs = repositories.findPersistedLogsAfter(now.minus(period));
                } catch (DateTimeParseException exc) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid period");
                }
            }
            return logs.stream()
                    .map(this::toString)
                    .collect(Collectors.joining("\n")) + "\n";
        } catch (ErrorResultException exc) {
            var status = exc.getStatus() != null ? exc.getStatus() : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status);
        }
    }

    private String toString(PersistedLog log) {
        var timestamp = log.getTimestamp().minusNanos(log.getTimestamp().getNano());
        return timestamp + "\t" + log.getUser().getLoginName() + "\t" + log.getMessage();
    }

    @PostMapping(
        path = "/admin/update-search-index",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> updateSearchIndex() {
        try {
            var adminUser = admins.checkAdminUser();

            search.updateSearchIndex(true);

            var result = ResultJson.success("Updated search index");
            admins.logAdminAction(adminUser, result);
            return ResponseEntity.ok(result);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @GetMapping(
        path = "/admin/extension/{namespaceName}/{extensionName}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ExtensionJson> getExtension(@PathVariable String namespaceName,
                                                      @PathVariable String extensionName) {
        try {
            admins.checkAdminUser();
            ExtensionJson json;
            var latest = repositories.findLatestVersion(namespaceName, extensionName, null, false, false);
            if (latest != null) {
                json = local.toExtensionVersionJson(latest, null, false);
                json.setAllTargetPlatformVersions(repositories.findTargetPlatformsGroupedByVersion(latest.getExtension()));
                json.setActive(latest.getExtension().isActive());
            } else {
                var extension = repositories.findExtension(extensionName, namespaceName);
                if (extension == null) {
                    var error = "Extension not found: " + NamingUtil.toExtensionId(namespaceName, extensionName);
                    throw new ErrorResultException(error, HttpStatus.NOT_FOUND);
                }

                json = new ExtensionJson();
                json.setNamespace(extension.getNamespace().getName());
                json.setName(extension.getName());
                json.setAllVersions(Collections.emptyMap());
                json.setAllTargetPlatformVersions(Collections.emptyList());
                json.setDeprecated(extension.isDeprecated());
                json.setActive(extension.isActive());
            }
            return ResponseEntity.ok(json);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(ExtensionJson.class);
        }
    }

    @PostMapping(
        path = "/admin/extension/{namespaceName}/{extensionName}/delete",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Delete an extension or one or multiple extension versions")
    @ApiResponse(
            responseCode = "200",
            description = "A success message is returned in JSON format",
            content = @Content(schema = @Schema(implementation = ResultJson.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "An error message is returned in JSON format",
            content = @Content(schema = @Schema(implementation = ResultJson.class))
    )
    @ApiResponse(
            responseCode = "404",
            description = "Extension not found",
            content = @Content()
    )
    public ResponseEntity<ResultJson> deleteExtension(
            @PathVariable @Parameter(description = "Namespace name", example = "julialang") String namespaceName,
            @PathVariable @Parameter(description = "Extension name", example = "language-julia") String extensionName,
            @RequestParam(value = "token", required = false) @Parameter(description = "A personal access token") String tokenValue,
            @RequestBody(required = false) List<TargetPlatformVersionJson> targetVersions
    ) {
        try {
            ResultJson result;
            var adminUser = tokenValue == null ? admins.checkAdminUser() : admins.checkAdminUser(tokenValue);
            if(targetVersions == null) {
                result = admins.deleteExtension(namespaceName, extensionName, adminUser);
            } else {
                var results = new ArrayList<ResultJson>();
                for(var targetVersion : targetVersions) {
                    results.add(admins.deleteExtension(namespaceName, extensionName, targetVersion.targetPlatform(), targetVersion.version(), adminUser));
                }

                result = new ResultJson();
                result.setError(results.stream().map(ResultJson::getError).filter(Objects::nonNull).collect(Collectors.joining("\n")));
                result.setSuccess(results.stream().map(ResultJson::getSuccess).filter(Objects::nonNull).collect(Collectors.joining("\n")));
            }

            return ResponseEntity.ok(result);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @GetMapping(
        path = "/admin/namespace/{namespaceName}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<NamespaceJson> getNamespace(@PathVariable String namespaceName) {
        try {
            admins.checkAdminUser();

            var namespace = local.getNamespace(namespaceName);
            var adminNamespaceUrl = createAdminNamespaceUrl(namespace);
            namespace.setMembersUrl(UrlUtil.createApiUrl(adminNamespaceUrl, "members"));
            namespace.setRoleUrl(UrlUtil.createApiUrl(adminNamespaceUrl, "change-member"));
            return ResponseEntity.ok(namespace);
        } catch (NotFoundException exc) {
            var json = NamespaceJson.error("Namespace not found: " + namespaceName);
            return new ResponseEntity<>(json, HttpStatus.NOT_FOUND);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(NamespaceJson.class);
        }
    }

    private String createAdminNamespaceUrl(NamespaceJson namespace) {
        return UrlUtil.createApiUrl(UrlUtil.getBaseUrl(), "admin", "namespace", namespace.getName());
    }

    @PostMapping(
        path = "/admin/create-namespace",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> createNamespace(@RequestBody NamespaceJson namespace) {
        try {
            admins.checkAdminUser();
            var json = admins.createNamespace(namespace);
            var url = createAdminNamespaceUrl(namespace);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create(url))
                    .body(json);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @PostMapping(
            path = "/admin/change-namespace",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> changeNamespace(@RequestBody ChangeNamespaceJson json) {
        try {
            admins.checkAdminUser();
            admins.changeNamespace(json);
            return ResponseEntity.ok(ResultJson.success("Scheduled namespace change from '" + json.oldNamespace() + "' to '" + json.newNamespace() + "'.\nIt can take 15 minutes to a couple hours for the change to become visible."));
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @GetMapping(
        path = "/admin/namespace/{namespaceName}/members",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Get members for a namespace")
    @ApiResponse(
            responseCode = "200",
            description = "A success message is returned in JSON format"
    )
    public ResponseEntity<NamespaceMembershipListJson> getNamespaceMembers(
            @PathVariable @Parameter(description = "Namespace name", example = "mtxr") String namespaceName,
            @RequestParam(value = "token", required = false) @Parameter(description = "A personal access token") String tokenValue
    ) {
        try{
            if(tokenValue == null) {
                admins.checkAdminUser();
            } else {
                admins.checkAdminUser(tokenValue);
            }

            var memberships = repositories.findMemberships(namespaceName);
            var membershipList = new NamespaceMembershipListJson();
            membershipList.setNamespaceMemberships(memberships.stream().map(NamespaceMembership::toJson).toList());
            return ResponseEntity.ok(membershipList);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(NamespaceMembershipListJson.class);
        }
    }

    @PostMapping(
        path = "/admin/namespace/{namespaceName}/change-member",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @CrossOrigin
    @Operation(summary = "Edit a member of a namespace")
    @ApiResponse(
            responseCode = "200",
            description = "A success message is returned in JSON format",
            content = @Content(schema = @Schema(implementation = ResultJson.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "An error message is returned in JSON format",
            content = @Content(schema = @Schema(implementation = ResultJson.class))
    )
    public ResponseEntity<ResultJson> editNamespaceMember(
            @PathVariable @Parameter(description = "Namespace name", example = "BeardedBear") String namespaceName,
            @RequestParam("user") @Parameter(description = "User name") String userName,
            @RequestParam(required = false) @Parameter(description = "Login provider name", example = "github") String provider,
            @RequestParam
            @Parameter(
                    description = "The role to assign to the user or remove the user from the namespace",
                    schema = @Schema(allowableValues = {NamespaceMembership.ROLE_CONTRIBUTOR, NamespaceMembership.ROLE_OWNER, "remove"})
            )
            String role,
            @RequestParam(value = "token", required = false) @Parameter(description = "A personal access token") String tokenValue
    ) {
        try {
            var adminUser = tokenValue == null ? admins.checkAdminUser() : admins.checkAdminUser(tokenValue);
            var result = admins.editNamespaceMember(namespaceName, userName, provider, role, adminUser);
            return ResponseEntity.ok(result);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }

    @GetMapping(
        path = "/admin/publisher/{provider}/{loginName}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<UserPublishInfoJson> getUserPublishInfo(@PathVariable String provider, @PathVariable String loginName) {
        try {
            admins.checkAdminUser();
            var userPublishInfo = admins.getUserPublishInfo(provider, loginName);
            return ResponseEntity.ok(userPublishInfo);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity(UserPublishInfoJson.class);
        }
    }

    @PostMapping(
        path = "/admin/publisher/{provider}/{loginName}/revoke",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(hidden = true)
    public ResponseEntity<ResultJson> revokePublisherContributions(@PathVariable String loginName, @PathVariable String provider) {
        try {
            var adminUser = admins.checkAdminUser();
            var result = admins.revokePublisherContributions(provider, loginName, adminUser);
            return ResponseEntity.ok(result);
        } catch (ErrorResultException exc) {
            return exc.toResponseEntity();
        }
    }
}