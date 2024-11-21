# TMail LSC plugin

This a plugin for LSC, using TMail REST API


### Goal

The object of this plugin is to synchronize addresses aliases and users from one referential to a [TMail server](https://tmail.linagora.com/).

### Address Aliases Synchronization

For example, it can be used to synchronize the aliases stored in the LDAP of an OBM instance to the TMail Server(s) of a TMail deployment.

#### Architecture

Given the following LDAP entry:
```
dn: uid=rkowalsky,ou=users,dc=linagora.com,dc=lng
[...]
mail: rkowalsky@linagora.com
mailAlias: remy.kowalsky@linagora.com
mailAlias: remy@linagora.com
```

This will be represented as the following TMail address alias:
```bash
$ curl -XGET http://ip:port/address/aliases/rkowalsky@linagora.com

[
  {"source":"remy.kowalsky@linagora.com"},
  {"source":"remy@linagora.com"}
]
```

As addresses aliases in TMail are only created if there are some sources, an LDAP entry without mailAlias attribute won't be synchronized.

The pivot used for the synchronization in the LSC connector is the email address, here `rkowalsky@linagora.com` stored in the `email` attribute.

The destination attribute for the LSC aliases connector is named `sources`.

### Users Synchronization

For example, it can be used to synchronize the users stored in the LDAP of an OBM instance to the TMail Server(s) of a TMail deployment.

#### Architecture
Given the following LDAP entries:

```
dn: uid=james-user, ou=people, dc=james,dc=org
mail: james-user@james.org
[...]

dn: uid=james-user2, ou=people, dc=james,dc=org
mail: james-user2@james.org
[...]

dn: uid=james-user3, ou=people, dc=james,dc=org
mail: james-user3@james.org
[...]
```

This will be represented as the following TMail users:

```bash
$ curl -XGET http://ip:port/users

[
  {"username":"james-user2@james.org"},
  {"username":"james-user4@james.org"}
]
```

If LDAP entry with the `mail` attribute exists but not synchronized, the user will be created with choose:
- Generating random password
- [Synchronizing existing password](https://github.com/lsc-project/lsc-james-plugin/issues/2)

If LDAP entry has no `mail` attribute corresponding, the user will be deleted.

Expected Result:

    - james-user@james.org -> create
    - james-user2@james.org -> nothing happens
    - james-user3@james.org -> create
    - james-user4@james.org -> delete

```bash 
$ curl -XGET http://ip:port/users

[
  {"username":"james-user@james.org"},
  {"username":"james-user2@james.org"},
  {"username":"james-user3@james.org"}
]
```

The pivot used for the synchronization in LSC connector is email address. For this case, `james-user@james.org` is stored in `email` attribute.

### Domain contact Synchronization

For example, it can be used to synchronize the domain contact stored in a LDAP instance to the TMail Server(s) of a TMail deployment in order to empower auto-complete.

#### Architecture
Given the following LDAP entries:

```
dn: uid=renecordier, ou=people, dc=james,dc=org
mail: renecordier@james.org
givenName: Rene
sn: Cordier
[...]

dn: uid=tungtranvan, ou=people, dc=james,dc=org
mail: tungtranvan@james.org
givenName: Tung
sn: Tran Van
[...]
```

This will be represented as the following TMail domain contacts:

```bash
$ curl -XGET http://ip:port/domains/contacts

["renecordier@james.org", "tungtranvan@james.org"]
```

Second contact (tungtranvan@james.org) details:
```bash
$ curl -XGET http://ip:port/domains/james.org/contacts/tungtranvan

{
    "id": "2",
    "emailAddress": "tungtranvan@james.org",
    "firstname": "Tung",
    "surname": "Tran Van"
}
```

LDAP entries's `givenName` and `sn` are Optional.

The pivot used for the synchronization in the LSC connector is the email address, here `tungtranvan@james.org` stored in the `email` attribute.

The destination attributes for the LSC aliases connector are named `firstname` and `surname`.

### Address Mappings Synchronization

For example, it can be used to synchronize the address mappings stored in the LDAP server to the TMail Server(s) of a TMail deployment.

#### Architecture

Given the following LDAP entry:
```
dn: uid=rkowalsky,ou=users,dc=linagora.com,dc=lng
[...]
mail: rkowalsky@linagora.com
otherMailbox: addressMapping1@linagora.com
otherMailbox: addressMapping2@linagora.com
```

This will be represented as the following TMail address mappings:
```bash
$ curl -XGET http://ip:port/mappings/user/rkowalsky@linagora.com

[
  {
    "type": "Address",
    "mapping": "addressMapping1@linagora.com"
  },
  {
    "type": "Address",
    "mapping": "addressMapping2@linagora.com"
  }
]
```

Please notice that users need to be created in James before creating address mappings for those users.

The pivot used for the synchronization in the LSC connector is the email address, here `rkowalsky@linagora.com` stored in the `email` attribute.

The destination attribute for the LSC address mappings connector is named `addressMappings`.

#### Supported operations
- **Update**: If a user has some address mappings in James, but there are some address mappings in LDAP that do not exist yet for the user in James side, those address mappings would be created.
  If a user has some address mappings in James but do not exist in LDAP, be careful that those address mappings in James would be removed.
- **Delete**: If a user exists in James but does not exist in LDAP, then all of his address mappings on James should be removed.

### JMAP Identity provisioning

For example, it can be used to provision default JMAP identity for users that leverages the names stored in an LDAP instance to the TMail Server(s) of a TMail deployment.

#### Architecture
Given the following LDAP entries:

```
dn: uid=tungtranvan, ou=people, dc=james,dc=org
mail: tungtranvan@james.org
givenName: Tung
sn: Tran Van
[...]
```

After running this identity synchronization job, a default identity will be created for the user:

```bash
$ curl -XGET http://ip:port/users/tungtranvan@james.org/identities?default=true

[{
	"name": "Tung Tran Van",
	"email": "tungtranvan@james.org",
	"id": "4c039533-75b9-45db-becc-01fb0e747aa8",
	"mayDelete": true,
	"textSignature": "",
	"htmlSignature": "",
	"sortOrder": 0,
	"bcc": [],
	"replyTo": []
}]
```

The pivot used for the synchronization in the LSC connector is the email address, here `tungtranvan@james.org` that stored in the `email` attribute.

The destination attributes for the LSC aliases connector are named `firstname` and `surname`.

### Forwards Synchronization

For example, it can be used to synchronize the forwards stored in the LDAP server to the TMail Server(s) of a TMail deployment.

#### Architecture

Given the following LDAP entry:
```
dn: uid=rkowalsky,ou=users,dc=linagora.com,dc=lng
[...]
mail: rkowalsky@linagora.com
otherMailbox: forward1@linagora.com
otherMailbox: rkowalsky@linagora.com
```

This will be represented as the following TMail address forwards:
```bash
$ curl -XGET http://ip:port/address/forwards/rkowalsky@linagora.com

[
  {"mailAddress":"forward1@linagora.com"}
]
```

Be default, local copy forwards from LDAP (e.g. `rkowalsky@linagora.com` in the above case) would not be synchronized.

To allow synchronizing local copy forwards, add
the following JVM property when run the LSC script: `-Dallow.synchronize.local.copy.forwards=true`.
Setting this property to `false` or omitting this property would not synchronize local copy forwards.

As addresses forwards in TMail are only created if there are some sources, an LDAP entry without `otherMailbox` attribute won't be synchronized.

Please notice that users need to be created in James before creating forwards for those users.

The pivot used for the synchronization in the LSC connector is the email address, here `rkowalsky@linagora.com` stored in the `email` attribute.

The destination attribute for the LSC forwards connector is named `forwards`.

#### Supported operations
- **Create**: If a user has no forward in James, but has some forwards in LDAP, then those forwards would be created on James.
- **Update**: If a user has some forwards in James, but there are some forwards in LDAP that do not exist yet for the user in James side, those forwards would be created.
  Note that we would not remove the forwards that are in James but not in LDAP, because those forwards could be user created forwards via JMAP.
- **Delete**: If a user does not exist in LDAP, then all of his forwards on James would be removed.

### Mail Quota Size Synchronization

For example, it can be used to synchronize the mail quota size stored in the LDAP server to the TMail Server(s) of a TMail deployment.

#### Architecture

Given the following LDAP entry:
```
dn: uid=rkowalsky,ou=users,dc=linagora.com,dc=lng
[...]
mail: rkowalsky@linagora.com
mailQuotaSize: 4000000000
```

This will be represented as the following TMail mail quota size:
```bash
$ curl -XGET http://ip:port/quota/users/rkowalsky@linagora.com/size

4000000000
```

The `mailQuotaSize` LDAP attribute will be used as source of truth for the synchronization.

Please notice that users need to be created in James before creating mail quota size for those users.

The pivot used for the synchronization in the LSC connector is the email address, here `rkowalsky@linagora.com` stored in the `email` attribute.

The destination attribute for the LSC forwards connector is named `mailQuotaSize`.

#### Supported operations
- **Create**: If a user has no mail quota size in TMail, but has mail quota size in LDAP, then it would be created on TMail.
- **Update**: 
  - If the admin changes a user's mail quota size on LDAP, the new mail quota size would be updated on TMail.
  - If the admin unset a user's mail quota size on LDAP, the mail quota size would be removed on TMail.
- **Delete**: If a user does not exist in LDAP, then his mail quota size on James would be removed.

### Configuration

The plugin connection needs a JWT token to connect to TMail. To configure this JWT token, set the `password` field of the plugin connection as the JWT token you want to use.

The `url` field of the plugin connection must be set to the URL of TMail' webadmin.

The `username` field of the plugin is ignored for now.

### Usage

There is an example of configuration in the `sample` directory. The `lsc.xml` file describe a synchronization from an OBM LDAP to a TMail server.
The values to configure are:
- `connections.ldapConnection.url`: The URL to the LDAP of OBM
- `connections.ldapConnection.username`: An LDAP user which is able to read the OBM aliases
- `connections.ldapConnection.password`: The password of this user

- `connections.pluginConnection.url`: The URL to the TMail Webadmin
- `connections.pluginConnection.password`: the JWT token used to connect the TMail Webadmin, it must includes an admin claim.

- `tasks.task.ldapSourceService.baseDn`: The search base of the users to synchronize.


The domains used in the aliases must have been previously created in TMail.
Otherwise, if a user have a single alias pointing to an unknown domain, none of her aliases will be added.

For the domain synchronization, you can specify the wished domain list to be synchronized by specify the dedicated ENV variable with key `DOMAIN_LIST_TO_SYNCHRONIZE` and DELIMITER `,`.
If you omit this environment variable setting, all domains contact will be synchronized from LDAP.

The jar of the TMail LSC plugin (`target/lsc-tmail-plugin-1.0-distribution.jar`) must be copied in the `lib` directory of your LSC installation.
Then you can launch it with the following command line:

```bash
JAVA_OPTS="-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated" bin/lsc --config /home/rkowalski/Documents/lsc-james-plugin/sample/ldap-to-james/ --synchronize all --clean all --threads 1
```

If don't want to delete dangling data, run this command without `--clean all` parameter.

### Packaging

We provide autonomously Docker image building thanks to Maven plugins. You need to run `mvn clean install` to build the image `linagora/tmail-lsc:latest`.

To use this image, please mount the appropriate LSC configuration files to container's `/opt/lsc/conf` directory. 

E.g:
```bash
docker run -it -v ${PWD}/sample/ldap-to-tmail-contact/logback.xml:/opt/lsc/conf/logback.xml -v ${PWD}/sample/ldap-to-tmail-contact/lsc.xml:/opt/lsc/conf/lsc.xml linagora/tmail-lsc:latest
```

Then run this command inside the container to run synchronization tasks:

```bash
JAVA_OPTS=$JAVA_OPTS ./lsc --config $CONF_DIR --synchronize all --clean all --threads 1
```

If don't want to delete dangling data on TMail, either turn the delete operation in `lsc.xml` to `false` or run the above command without `--clean all` parameter.
