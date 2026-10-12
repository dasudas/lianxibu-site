package com.lianxibu.site.service.jobs;
class OwnerAdHocCreateHttpMySqlTest extends OwnerAdHocCreateHttpTest {
    @Override OwnerAdHocCreationTest database(){return new OwnerAdHocCreationMySqlTest();}
}
