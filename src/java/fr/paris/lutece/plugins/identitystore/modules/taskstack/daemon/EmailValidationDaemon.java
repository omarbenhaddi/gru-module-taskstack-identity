package fr.paris.lutece.plugins.identitystore.modules.taskstack.daemon;

import fr.paris.lutece.plugins.identitystore.business.identity.IdentityAttributeHome;
import fr.paris.lutece.plugins.identitystore.business.identity.IdentityHome;
import fr.paris.lutece.plugins.identitystore.modules.taskstack.provider.EmailValidationRequestManagement;
import fr.paris.lutece.plugins.identitystore.service.daemon.LoggingDaemon;
import fr.paris.lutece.plugins.identitystore.v3.web.rs.dto.history.AttributeChange;
import fr.paris.lutece.plugins.identitystore.v3.web.rs.dto.history.AttributeChangeType;
import fr.paris.lutece.plugins.identitystore.v3.web.rs.dto.task.IdentityResourceType;
import fr.paris.lutece.plugins.identitystore.v3.web.rs.dto.task.IdentityTaskType;
import fr.paris.lutece.plugins.identitystore.v3.web.rs.util.Constants;
import fr.paris.lutece.plugins.identitystore.web.exception.IdentityStoreException;
import fr.paris.lutece.plugins.taskstack.business.task.TaskStatusType;
import fr.paris.lutece.plugins.taskstack.dto.TaskDto;
import fr.paris.lutece.plugins.taskstack.exception.TaskStackException;
import fr.paris.lutece.plugins.taskstack.exception.TaskValidationException;
import fr.paris.lutece.plugins.taskstack.rs.request.common.AuthorType;
import fr.paris.lutece.plugins.taskstack.rs.request.common.RequestAuthor;
import fr.paris.lutece.plugins.taskstack.service.TaskService;
import fr.paris.lutece.portal.service.spring.SpringContextService;
import fr.paris.lutece.portal.service.util.AppPropertiesService;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.time.StopWatch;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class EmailValidationDaemon extends LoggingDaemon {

    private final SimpleDateFormat metadataDateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss" );

    private final String authorName = AppPropertiesService.getProperty("daemon.emailValidationDaemon.author.name");
    private final String clientCode = AppPropertiesService.getProperty("daemon.emailValidationDaemon.client.code");
    private final int taskExpirationMonth = AppPropertiesService.getPropertyInt("daemon.emailValidationDaemon.task.expiration.month", 6);
    private final int batchLimit = AppPropertiesService.getPropertyInt("daemon.emailValidationDaemon.batch.limit", 200);

    @Override
    public void doTask() {
        final StopWatch stopWatch = StopWatch.createStarted();
        this.info("Starting email validation daemon...");

        final List<String> cuidList = IdentityHome.findNotMergedNotConnectedWithNonCertifiedAttributeCustomerIds(Constants.PARAM_EMAIL, batchLimit);
        if (cuidList.isEmpty()) {
            this.info("No suitable identity found for email validation daemon.");
        } else {
            this.info(cuidList.size() + " suitable identities found for email validation daemon. Batch limit = " + batchLimit);
            final EmailValidationRequestManagement requestManagement = SpringContextService.getContext().getBean(EmailValidationRequestManagement.class);
            final RequestAuthor requestAuthor = buildAuthor(System.currentTimeMillis());
            int skippedExistingTask = 0;
            int skippedError = 0;
            int skippedNonValidate = 0;
            int created = 0;
            for (final String cuid : cuidList) {
                try {
                    final List<Integer> existingTaskIds =
                            TaskService.instance().searchId(null, cuid, IdentityResourceType.CUID.name(), IdentityTaskType.EMAIL_VALIDATION_REQUEST.name(),
                                                            null, null, null, List.of(TaskStatusType.TODO, TaskStatusType.IN_PROGRESS),
                                                            null, null, null, 1);
                    if (!existingTaskIds.isEmpty()) {
                        skippedExistingTask++;
                        continue;
                    }
                } catch (final TaskStackException e) {
                    this.error("error while searching for existing tasks :: " + e.getMessage());
                    skippedError++;
                    continue;
                }

                final TaskDto task = new TaskDto();
                task.setTaskType(IdentityTaskType.EMAIL_VALIDATION_REQUEST.name());
                task.setResourceType(IdentityResourceType.CUID.name());
                task.setResourceId(cuid);

                try {
                    requestManagement.doBefore(task);
                } catch (final TaskValidationException e) {
                    this.error("The identity [CUID=" + cuid + "] has failed to validate the email validation request :: " + e.getMessage());
                    skippedNonValidate++;
                    continue;
                }

                final List<AttributeChange> attributeHistory;
                try {
                    attributeHistory = IdentityAttributeHome.getAttributeChangeHistory(cuid);
                } catch (final IdentityStoreException e) {
                    this.error("error while retrieving attribute history for identity [CUID=" + cuid + "] :: " + e.getMessage());
                    skippedError++;
                    continue;
                }
                attributeHistory.stream()
                                .filter(a -> a.getAttributeKey().equals(Constants.PARAM_EMAIL))
                                .filter(a -> List.of(AttributeChangeType.CREATE, AttributeChangeType.UPDATE).contains(a.getChangeType()))
                                .findFirst()
                                .ifPresent(
                                        lastEmailChange -> task.setMetadata(
                                                Map.of(Constants.METADATA_LAST_UPDATE_CLIENT_CODE, lastEmailChange.getClientCode(),
                                                       Constants.METADATA_LAST_UPDATE_DATE, metadataDateFormat.format(lastEmailChange.getModificationDate()))));
                task.setExpirationDate(Timestamp.valueOf(LocalDateTime.now().plusMonths(taskExpirationMonth)));
                try {
                    TaskService.instance().createTask(task, requestAuthor, clientCode);
                } catch (final TaskStackException e) {
                    this.error("error while creating the task for identity [CUID="+cuid+"] :: " + e.getMessage());
                    skippedError++;
                    continue;
                }
                created++;
            }
            this.info("-- " + created + " tasks successfully created.");
            this.info("-- " + skippedExistingTask + " tasks not created because already existing.");
            this.info("-- " + skippedError + " tasks not created because error occurred.");
            this.info("-- " + skippedNonValidate + " tasks not created because of failing email validation.");
        }

        stopWatch.stop();
        final String execTime = "Execution time " + DurationFormatUtils.formatDurationWords(stopWatch.getTime(), true, true);
        this.info("Email validation daemon completed. " + execTime );
    }

    private RequestAuthor buildAuthor(final long time)
    {
        final RequestAuthor author = new RequestAuthor( );
        author.setType(AuthorType.application);
        author.setName( authorName + DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT.format( time ) );
        return author;
    }


}
