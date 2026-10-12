package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SharedWritePreparationMySqlTest extends SharedWritePreparationTest {@Override boolean mysql(){return true;}@Test void ActualIndependentCommitDuringPreparationWins()throws Exception{NativeReceiptCommittedDuringPreparationIsRecheckedBeforeOriginalWriterWork();}}
