package org.joget.marketplace;

import java.util.Map;
import org.joget.apps.app.service.AppUtil;
import org.joget.directory.model.service.ExtDirectoryManager;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.springframework.context.ApplicationContext;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.AttributeType;
import com.amazonaws.services.cognitoidp.model.DescribeUserPoolRequest;
import com.amazonaws.services.cognitoidp.model.DescribeUserPoolResult;
import com.amazonaws.services.cognitoidp.model.GroupType;
import com.amazonaws.services.cognitoidp.model.ListGroupsRequest;
import com.amazonaws.services.cognitoidp.model.ListGroupsResult;
import com.amazonaws.services.cognitoidp.model.ListUsersInGroupRequest;
import com.amazonaws.services.cognitoidp.model.ListUsersInGroupResult;
import com.amazonaws.services.cognitoidp.model.ListUsersRequest;
import com.amazonaws.services.cognitoidp.model.ListUsersResult;
import com.amazonaws.services.cognitoidp.model.UserPoolType;
import com.amazonaws.services.cognitoidp.model.UserType;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joget.commons.util.LogUtil;
import org.joget.directory.dao.EmploymentDao;
import org.joget.directory.dao.GroupDao;
import org.joget.directory.dao.RoleDao;
import org.joget.directory.dao.UserDao;
import org.joget.directory.model.Employment;
import org.joget.directory.model.Group;
import org.joget.directory.model.Organization;
import org.joget.directory.model.Role;
import org.joget.directory.model.User;

public class AwsCognitorUserSyncTool extends DefaultApplicationPlugin {

    @Override
    public Object execute(Map properties) {

        String organization = (String) properties.get("organization");
        String region = (String) properties.get("region");
        String accessKey = (String) properties.get("accessKey");
        String secretKey = (String) properties.get("secretKey");
        String poolId = (String) properties.get("poolId");

        ApplicationContext ac = AppUtil.getApplicationContext();
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) ac.getBean("directoryManager");
        UserDao userDao = (UserDao) ac.getBean("userDao");
        GroupDao groupDao = (GroupDao) ac.getBean("groupDao");
        EmploymentDao employmentDao = (EmploymentDao) ac.getBean("employmentDao");

        Map<String, Integer> cognitoUsers = new HashMap<>();

        AWSCognitoIdentityProvider client = createCognitoClient(accessKey, secretKey, region);

        if (client != null) {

            // verify the poolId first
            boolean validPoolId = true;
            try {
                DescribeUserPoolRequest dupr = new DescribeUserPoolRequest();
                dupr.setUserPoolId(poolId);
                DescribeUserPoolResult describeUserPoolResult = client.describeUserPool(dupr);
                UserPoolType userPoolType = describeUserPoolResult.getUserPool();
            } catch (Exception e) {
                validPoolId = false;
                LogUtil.info(getClassName(), "Invalid user pool ID");
            }

            if (validPoolId) {
                // clear the existing data ( groups, users) for the selected organization
                clearExistingData(organization);

                Organization org = directoryManager.getOrganization(organization);
                // get the groups
                ListGroupsRequest lgr = new ListGroupsRequest();
                lgr.setUserPoolId(poolId);
                ListGroupsResult listGroups = client.listGroups(lgr);
                List<GroupType> getGroups = listGroups.getGroups();

                for (GroupType groupType : getGroups) {
                    String groupName = groupType.getGroupName();
                    // create group
                    Group group = new Group();
                    group.setId(groupName);
                    group.setName(groupName);
                    group.setOrganization(org);
                    groupDao.addGroup(group);

                    // create the group and set the users
                    ListUsersInGroupRequest luigr = new ListUsersInGroupRequest();
                    luigr.setGroupName(groupName);
                    luigr.setUserPoolId(poolId);
                    ListUsersInGroupResult listUsersInGroup = client.listUsersInGroup(luigr);
                    List<UserType> groupUsers = listUsersInGroup.getUsers();
                    for (UserType userType : groupUsers) {
                        User jogetUser = new User();
                        String username = userType.getUsername();
                        boolean enabled = userType.getEnabled();
                        int status = enabled ? 1 : 0;
                        jogetUser.setActive(status);
                        cognitoUsers.put(username, status);
                        List<AttributeType> atrs = userType.getAttributes();
                        prepareUser(atrs, jogetUser, group, org);
                        userDao.addUser(jogetUser);

                        Employment employment = new Employment();
                        employment.setUserId(jogetUser.getId());
                        employment.setOrganizationId(organization);
                        employment.setUser(jogetUser);
                        employmentDao.addEmployment(employment);

                    }
                }

                // process users who are not assigned to any group
                ListUsersRequest lur = new ListUsersRequest();
                lur.setUserPoolId(poolId);
                ListUsersResult listUsers = client.listUsers(lur);
                List<UserType> allUsers = listUsers.getUsers();
                for (UserType userType : allUsers) {
                    User jogetUser = new User();
                    String username = userType.getUsername();
                    if (!cognitoUsers.containsKey(username)) {
                        boolean enabled = userType.getEnabled();
                        int status = enabled ? 1 : 0;
                        jogetUser.setActive(status);
                        cognitoUsers.put(username, status);
                        List<AttributeType> atrs = userType.getAttributes();
                        prepareUser(atrs, jogetUser, null, org);
                        userDao.deleteUser(jogetUser.getUsername());
                        userDao.addUser(jogetUser);

                        Employment employment = new Employment();
                        employment.setUserId(jogetUser.getId());
                        employment.setOrganizationId(organization);
                        employment.setUser(jogetUser);
                        employmentDao.addEmployment(employment);
                    }
                }
                client = null;
            }
        } else {
            LogUtil.info(getClassName(), "Unable to connec to AWS Cognito. Please check your credentials.");
        }
        return null;
    }

    public void clearExistingData(String organizationId) {
        ApplicationContext ac = AppUtil.getApplicationContext();
        UserDao userDao = (UserDao) ac.getBean("userDao");
        GroupDao groupDao = (GroupDao) ac.getBean("groupDao");
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) ac.getBean("directoryManager");

        Collection<User> userList = directoryManager.getUserByOrganizationId(organizationId);
        Collection<Group> groupList = directoryManager.getGroupsByOrganizationId(null, organizationId, "name", Boolean.FALSE, 0, -1);

        // remove the users
        for (User user : userList) {
            userDao.deleteUser(user.getUsername());
        }

        // remove the groups
        for (Group group : groupList) {
            groupDao.deleteGroup(group.getId());
        }
    }

    private void prepareUser(List<AttributeType> atrs, User jogetUser, Group group, Organization organization) {
        String firstName = "";
        String lastName = "";
        String email = "";
        for (AttributeType at : atrs) {
            String attributeName = at.getName();
            String attributeValue = at.getValue();

            if (attributeValue != null && attributeName.equals("given_name")) {
                firstName = attributeValue;
            } else if (attributeValue != null && attributeName.equals("family_name")) {
                lastName = attributeValue;
            } else if (attributeValue != null && attributeName.equals("email")) {
                email = attributeValue;
            }
        }

        RoleDao roleDao = (RoleDao) AppUtil.getApplicationContext().getBean("roleDao");
        Set roleSet = new HashSet();
        Role r = roleDao.getRole("ROLE_USER");
        if (r != null) {
            roleSet.add(r);
        }
        jogetUser.setRoles(roleSet);
        if (group != null) {
            Set groupSet = new HashSet();
            groupSet.add(group);
            jogetUser.setGroups(groupSet);
        }

        jogetUser.setId(email);
        jogetUser.setUsername(email);
        jogetUser.setTimeZone("0");
        jogetUser.setEmail(email);
        jogetUser.setFirstName(firstName);
        jogetUser.setLastName(lastName);

    }

    private static AWSCognitoIdentityProvider createCognitoClient(String accessKey, String secretKey, String region) {
        Regions regions = getRegionFromValue(region);
        AWSCredentials cred = new BasicAWSCredentials(accessKey, secretKey);
        AWSCredentialsProvider credProvider = new AWSStaticCredentialsProvider(cred);
        return AWSCognitoIdentityProviderClientBuilder.standard()
                .withCredentials(credProvider)
                .withRegion(regions)
                .build();
    }

    private static Regions getRegionFromValue(String regionValue) {
        for (Regions region : Regions.values()) {
            String regionName = region.getName().toUpperCase();
            String finalRegion = regionName.replace('-', '_');
            if (finalRegion.equalsIgnoreCase(regionValue)) {
                return region;
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "AWS Cognito Users Sync Tool";
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return "To sync all the users and groups from AWS Cognito";
    }

    @Override
    public String getLabel() {
        return "AWS Cognito Users Sync Tool";
    }

    @Override
    public String getClassName() {
        return this.getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/AwsCognitorUserSyncTool.json", null, true, "messages/AwsCognitorUserSyncTool");
    }

}
