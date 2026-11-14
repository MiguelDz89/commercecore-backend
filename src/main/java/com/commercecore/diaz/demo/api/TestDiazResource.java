package com.commercecore.diaz.demo.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/test")
public class TestDiazResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Prueba Test Diaz";
    }

}
