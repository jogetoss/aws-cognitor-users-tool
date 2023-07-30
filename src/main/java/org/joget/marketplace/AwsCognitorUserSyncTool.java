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
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.AttributeType;
import com.amazonaws.services.cognitoidp.model.GroupType;
import com.amazonaws.services.cognitoidp.model.ListGroupsRequest;
import com.amazonaws.services.cognitoidp.model.ListGroupsResult;
import com.amazonaws.services.cognitoidp.model.ListUsersInGroupRequest;
import com.amazonaws.services.cognitoidp.model.ListUsersInGroupResult;
import com.amazonaws.services.cognitoidp.model.ListUsersRequest;
import com.amazonaws.services.cognitoidp.model.ListUsersResult;
import com.amazonaws.services.cognitoidp.model.UserType;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joget.commons.util.PluginThread;
import org.joget.directory.dao.GroupDao;
import org.joget.directory.dao.RoleDao;
import org.joget.directory.dao.UserDao;
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

        Map<String, Integer> cognitoUsers = new HashMap<>();

        // prepare aws cognito client
        AWSCognitoIdentityProvider client = createCognitoClient(accessKey, secretKey, region);
        
        if (client != null) {
            // get the groups
            ListGroupsRequest lgr = new ListGroupsRequest();
            lgr.setUserPoolId(poolId);
            ListGroupsResult listGroups = client.listGroups(lgr);
            List<GroupType> getGroups = listGroups.getGroups();

            for (GroupType groupType : getGroups) {

                // first delete the users
                String groupName = groupType.getGroupName();
                Collection<User> usersByGroupId = directoryManager.getUserByGroupId(groupName);
                for (User user : usersByGroupId) {
                    userDao.deleteUser(user.getUsername());
                }

                // delete the group 
                groupDao.deleteGroup(groupName);

                // get Org
                Organization org = directoryManager.getOrganization(organization);

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
                    prepareUser(atrs, jogetUser, group);
                    userDao.addUser(jogetUser);
                }
            }

            // process users who are not allocated to any group
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
                    prepareUser(atrs, jogetUser, null);
                    userDao.deleteUser(jogetUser.getUsername());
                    userDao.addUser(jogetUser);
                }
            }
            client = null;
        }

        return null;
    }

    private void prepareUser(List<AttributeType> atrs, User jogetUser, Group group) {
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
