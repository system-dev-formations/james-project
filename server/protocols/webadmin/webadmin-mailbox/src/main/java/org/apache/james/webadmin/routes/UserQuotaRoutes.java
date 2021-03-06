/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.webadmin.routes;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.dto.QuotaDTO;
import org.apache.james.webadmin.service.UserQuotaService;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.ErrorResponder.ErrorType;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.apache.james.webadmin.validation.QuotaValue;
import org.apache.james.webadmin.validation.QuotaValue.QuotaCount;
import org.apache.james.webadmin.validation.QuotaValue.QuotaSize;
import org.eclipse.jetty.http.HttpStatus;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import spark.Request;
import spark.Service;

@Api(tags = "UserQuota")
@Path(UserQuotaRoutes.QUOTA_ENDPOINT)
@Produces("application/json")
public class UserQuotaRoutes implements Routes {

    private static final String USER = "user";
    static final String QUOTA_ENDPOINT = "/quota/users/:" + USER;
    private static final String COUNT_ENDPOINT = QUOTA_ENDPOINT + "/count";
    private static final String SIZE_ENDPOINT = QUOTA_ENDPOINT + "/size";

    private final UsersRepository usersRepository;
    private final UserQuotaService userQuotaService;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<QuotaDTO> jsonExtractor;
    private Service service;

    @Inject
    public UserQuotaRoutes(UsersRepository usersRepository, UserQuotaService userQuotaService, JsonTransformer jsonTransformer) {
        this.usersRepository = usersRepository;
        this.userQuotaService = userQuotaService;
        this.jsonTransformer = jsonTransformer;
        this.jsonExtractor = new JsonExtractor<>(QuotaDTO.class);
    }

    @Override
    public void define(Service service) {
        this.service = service;

        defineGetQuotaCount();
        defineDeleteQuotaCount();
        defineUpdateQuotaCount();

        defineGetQuotaSize();
        defineDeleteQuotaSize();
        defineUpdateQuotaSize();

        defineGetQuota();
        defineUpdateQuota();
    }

    @PUT
    @ApiOperation(value = "Updating count and size at the same time")
    @ApiImplicitParams({
            @ApiImplicitParam(required = true, dataType = "org.apache.james.webadmin.dto.QuotaDTO", paramType = "body")
    })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.NO_CONTENT_204, message = "OK. The value has been updated."),
            @ApiResponse(code = HttpStatus.BAD_REQUEST_400, message = "The body is not a positive integer or not unlimited value (-1)."),
            @ApiResponse(code = HttpStatus.CONFLICT_409, message = "The requested restriction can't be enforced right now."),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineUpdateQuota() {
        service.put(QUOTA_ENDPOINT, ((request, response) -> {
            String user = checkUserExist(request);
            QuotaDTO quotaDTO = parseQuotaDTO(request);
            userQuotaService.defineQuota(user, quotaDTO);
            response.status(HttpStatus.NO_CONTENT_204);
            return response;
        }));
    }

    @GET
    @ApiOperation(
        value = "Reading count and size at the same time",
        notes = "If there is no limitation for count and/or size, the returned value will be -1"
    )
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.OK_200, message = "OK", response = QuotaDTO.class),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineGetQuota() {
        service.get(QUOTA_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            return userQuotaService.getQuota(user);
        }, jsonTransformer);
    }

    @DELETE
    @Path("/size")
    @ApiOperation(value = "Removing per user mail size limitation by updating to unlimited value")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.NO_CONTENT_204, message = "The value is updated to unlimited value."),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineDeleteQuotaSize() {
        service.delete(SIZE_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            userQuotaService.deleteMaxSizeQuota(user);
            response.status(HttpStatus.NO_CONTENT_204);
            return response;
        });
    }

    @PUT
    @Path("/size")
    @ApiOperation(value = "Updating per user mail size limitation")
    @ApiImplicitParams({
            @ApiImplicitParam(required = true, dataType = "integer", paramType = "body")
    })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.NO_CONTENT_204, message = "OK. The value has been updated."),
            @ApiResponse(code = HttpStatus.BAD_REQUEST_400, message = "The body is not a positive integer."),
            @ApiResponse(code = HttpStatus.CONFLICT_409, message = "The requested restriction can't be enforced right now."),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineUpdateQuotaSize() {
        service.put(SIZE_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            QuotaSize quotaSize = QuotaValue.quotaSize(request.body());
            userQuotaService.defineMaxSizeQuota(user, quotaSize);
            response.status(HttpStatus.NO_CONTENT_204);
            return response;
        });
    }

    @GET
    @Path("/size")
    @ApiOperation(value = "Reading per user mail size limitation")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.OK_200, message = "OK", response = Long.class),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineGetQuotaSize() {
        service.get(SIZE_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            return userQuotaService.getMaxSizeQuota(user);
        }, jsonTransformer);
    }

    @DELETE
    @Path("/count")
    @ApiOperation(value = "Removing per user mail count limitation by updating to unlimited value")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.NO_CONTENT_204, message = "The value is updated to unlimited value."),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineDeleteQuotaCount() {
        service.delete(COUNT_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            userQuotaService.deleteMaxCountQuota(user);
            response.status(HttpStatus.NO_CONTENT_204);
            return response;
        });
    }

    @PUT
    @Path("/count")
    @ApiOperation(value = "Updating per user mail count limitation")
    @ApiImplicitParams({
            @ApiImplicitParam(required = true, dataType = "integer", paramType = "body")
    })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.NO_CONTENT_204, message = "OK. The value has been updated."),
            @ApiResponse(code = HttpStatus.BAD_REQUEST_400, message = "The body is not a positive integer."),
            @ApiResponse(code = HttpStatus.CONFLICT_409, message = "The requested restriction can't be enforced right now."),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineUpdateQuotaCount() {
        service.put(COUNT_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            QuotaCount quotaCount = QuotaValue.quotaCount(request.body());
            userQuotaService.defineMaxCountQuota(user, quotaCount);
            response.status(HttpStatus.NO_CONTENT_204);
            return response;
        });
    }

    @GET
    @Path("/count")
    @ApiOperation(value = "Reading per user mail count limitation")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.OK_200, message = "OK", response = Long.class),
            @ApiResponse(code = HttpStatus.INTERNAL_SERVER_ERROR_500, message = "Internal server error - Something went bad on the server side.")
    })
    public void defineGetQuotaCount() {
        service.get(COUNT_ENDPOINT, (request, response) -> {
            String user = checkUserExist(request);
            return userQuotaService.getMaxCountQuota(user);
        }, jsonTransformer);
    }

    private String checkUserExist(Request request) throws UsersRepositoryException {
        String user = request.params(USER);
        if (!usersRepository.contains(user)) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorType.NOT_FOUND)
                .message("User not found")
                .haltError();
        }
        return user;
    }

    private QuotaDTO parseQuotaDTO(Request request) {
        try {
            return jsonExtractor.parse(request.body());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorType.INVALID_ARGUMENT)
                .message("Quota should be positive or unlimited (-1)")
                .cause(e)
                .haltError();
        } catch (JsonExtractException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorType.INVALID_ARGUMENT)
                .message("Malformed JSON input")
                .cause(e)
                .haltError();
        }
    }

}
