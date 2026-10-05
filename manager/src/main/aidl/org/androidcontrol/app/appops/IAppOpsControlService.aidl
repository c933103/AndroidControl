package org.androidcontrol.app.appops;

interface IAppOpsControlService {
    void destroy() = 16777114;
    String execute(String request) = 1;
}
