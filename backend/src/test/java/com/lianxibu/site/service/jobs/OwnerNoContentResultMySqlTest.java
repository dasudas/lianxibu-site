package com.lianxibu.site.service.jobs;
class OwnerNoContentResultMySqlTest extends OwnerNoContentResultTest {
    @Override ResultJdbcStoreTest fixture(){var value=new NonContentManualProofFixture();value.fixture();return value;}
}
