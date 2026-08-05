const React = require('react');
const {View} = require('react-native');

function MapView(props) {
  return React.createElement(View, props, props.children);
}

function Marker(props) {
  return React.createElement(View, props);
}

module.exports = MapView;
module.exports.default = MapView;
module.exports.Marker = Marker;
