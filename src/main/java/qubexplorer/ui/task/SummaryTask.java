package qubexplorer.ui.task;

import java.lang.reflect.InvocationTargetException;
import org.openide.util.Exceptions;
import org.openide.windows.WindowManager;
import qubexplorer.IssuesContainer;
import qubexplorer.NoSuchProjectException;
import qubexplorer.Summary;
import qubexplorer.filter.IssueFilter;
import qubexplorer.server.SonarQube;
import qubexplorer.ui.AuthenticationRepository;
import qubexplorer.ui.ProjectChooser;
import qubexplorer.ui.ProjectContext;
import qubexplorer.ui.SonarIssuesTopComponent;

/**
 *
 * @author Victor
 */
public class SummaryTask extends Task<Summary>{
    private final IssuesContainer issuesContainer;
    private final IssueFilter[] filters;

    public SummaryTask(IssuesContainer issuesContainer, ProjectContext projectContext, IssueFilter[] filters) {
        super(projectContext, issuesContainer instanceof SonarQube? ((SonarQube)issuesContainer).getServerUrl(): null);
        this.issuesContainer = issuesContainer;
        this.filters = filters;
    }
    
    @Override
    public Summary execute() {
        return issuesContainer.getSummary(getToken(), getProjectContext().getProjectKey(), filters);
    }
    
    @Override
    protected void success(Summary summary) {
        SonarIssuesTopComponent sonarTopComponent = (SonarIssuesTopComponent) WindowManager.getDefault().findTopComponent("SonarIssuesTopComponent");
        sonarTopComponent.setProjectContext(getProjectContext());
        sonarTopComponent.setIssuesContainer(issuesContainer);
        sonarTopComponent.open();
        sonarTopComponent.requestVisible();
        sonarTopComponent.showSummary(summary);
    }

    @Override
    protected void fail(Throwable cause) {
        if(cause instanceof NoSuchProjectException) {
            assert issuesContainer instanceof SonarQube;
            SonarQube sonarQube=(SonarQube) issuesContainer;
            if(getToken()!= null) {
                AuthenticationRepository.getInstance().saveAuthentication(sonarQube.getServerUrl(), null, getToken());
            }
            ProjectChooser chooser=new ProjectChooser(WindowManager.getDefault().getMainWindow(), true);
            chooser.setSelectedUrl(sonarQube.getServerUrl());
            chooser.setServerUrlEnabled(false);
            chooser.loadProjectKeys();
            if(chooser.showDialog() == ProjectChooser.Option.ACCEPT) {
                ProjectContext newProjectContext = new ProjectContext(getProjectContext().getProject(), chooser.getSelectedProjectKey());
                TaskExecutor.execute(new SummaryTask(issuesContainer, newProjectContext, filters));
            }
        }else{
            super.fail(cause);
        }
    }
    
}