package com.e2eq.framework.security;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode( callSuper = true)
@Data
public class  TestPrincipal extends TestBase{
   String id;
   String name;
   List<String> roles = new ArrayList<>();
}
