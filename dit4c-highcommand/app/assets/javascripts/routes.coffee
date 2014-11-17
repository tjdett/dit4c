define(['./app'], (app) ->
  'use strict'
  app.config(['$routeProvider', ($routeProvider) ->
    $routeProvider.when('/',
      templateUrl: 'index.html'
      controller: 'IndexCtrl'
    )

    $routeProvider.when('/login',
      templateUrl: 'login.html'
      controller: 'LoginCtrl',
      resolve: {
        notLoggedIn: (AuthSrv, $location) ->
          AuthSrv.updateUser().then((user) ->
            $location.path('/containers').replace() if user
          )
      }
    )

    $routeProvider.when('/logout',
      redirectTo: '/',
      resolve: {
        logout: (AuthSrv) ->
          AuthSrv.logout()
      }
    )

    $routeProvider.when('/containers',
      templateUrl: 'containers.html'
      controller: 'ContainersCtrl',
      resolve:
        containers: ($http, $location) ->
          $http
            .get('/containers')
            .then (response) ->
              response.data
            .catch (e) ->
              $location.path('/login').replace() if e.status == 403
              []
        computeNodes: ($http, $q) ->
          $http
            .get('/compute-nodes')
            .then (response) ->
              nodes = response.data
              usableNodes = []
              insertInOrder = (node) ->
                i = usableNodes
                  .filter (n) -> n.name < node.name
                  .length
                usableNodes.splice(i, 0, node)
              nodes
                .filter (node) -> node.usable
                .forEach (node) ->
                  $http
                    .get('/compute-nodes/'+node.id+'/images')
                    .then (response) ->
                      node.images = response.data.filter (image) ->
                        # Only include downloaded images
                        image.metadata
                      if (node.images.length > 0)
                        insertInOrder(node)
              usableNodes
    )

    $routeProvider.when('/compute-nodes',
      templateUrl: 'compute-nodes.html'
      controller: 'ComputeNodesCtrl',
      resolve:
        computeNodes: ($http, $location) ->
          $http
            .get('/compute-nodes')
            .then (response) ->
              response.data
            .catch (e) ->
              $location.path('/login').replace() if e.status == 403
              []
    )

    $routeProvider.when('/notfound',
      templateUrl: 'notfound.html'
    )

    $routeProvider.otherwise(
      redirectTo: '/notfound'
    )
  ])
)
