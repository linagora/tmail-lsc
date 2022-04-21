# James LSC plugin

[![Build Status](https://travis-ci.org/lsc-project/lsc-james-plugin.svg?branch=master)](https://travis-ci.org/lsc-project/lsc-james-plugin)

This a plugin for LSC, using James REST API


### Goal

The object of this plugin is to synchronize addresses aliases and users from one referential to a [James server](https://james.apache.org/).

### Address Aliases Synchronization

For example, it can be used to synchronize the aliases stored in the LDAP of an OBM instance to the James Server(s) of a TMail deployment.

#### Architecture

Given the following LDAP entry:
```
dn: uid=rkowalsky,ou=users,dc=linagora.com,dc=lng
[...]
mail: rkowalsky@linagora.com
mailAlias: remy.kowalsky@linagora.com
mailAlias: remy@linagora.com
```

This will be represented as the following James address alias:
```bash
$ curl -XGET http://ip:port/address/aliases/rkowalsky@linagora.com

[
  {"source":"remy.kowalsky@linagora.com"},
  {"source":"remy@linagora.com"}
]
```

As addresses aliases in James are only created if there are some sources, an LDAP entry without mailAlias attribute won't be synchronized.

The pivot used for the synchronization in the LSC connector is the email address, here `rkowalsky@linagora.com` stored in the `email` attribute.

The destination attribute for the LSC aliases connector is named `sources`.

### Users Synchronization

For example, it can be used to synchronize the users stored in the LDAP of an OBM instance to the James Server(s) of a TMail deployment.

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

This will be represented as the following James users:

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

### Configuration

The plugin connection needs a JWT token to connect to James. To configure this JWT token, set the `password` field of the plugin connection as the JWT token you want to use.

The `url` field of the plugin connection must be set to the URL of James' webadmin.

The `username` field of the plugin is ignored for now.

### Usage

There is an example of configuration in the `sample` directory. The `lsc.xml` file describe a synchronization from an OBM LDAP to a James server.
The values to configure are:
- `connections.ldapConnection.url`: The URL to the LDAP of OBM
- `connections.ldapConnection.username`: An LDAP user which is able to read the OBM aliases
- `connections.ldapConnection.password`: The password of this user

- `connections.pluginConnection.url`: The URL to the James Webadmin
- `connections.pluginConnection.password`: the JWT token used to connect the James Webadmin, it must includes an admin claim.

- `tasks.task.ldapSourceService.baseDn`: The search base of the users to synchronize.


The domains used in the aliases must have been previously created in James.
Otherwise, if a user have a single alias pointing to an unknown domain, none of her aliases will be added.

The jar of the James LSC plugin must be copied in the `lib` directory of your LSC installation.
Then you can launch it with the following command line:

```bash
JAVA_OPTS="-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated" bin/lsc --config /home/rkowalski/Documents/lsc-james-plugin/sample/ldap-to-james/ --synchronize all --clean all --threads 1
```

If don't want to delete dangling data, run this command without `--clean all` parameter.

### Packaging

WIP