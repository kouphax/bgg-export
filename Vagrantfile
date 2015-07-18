
Vagrant.configure("2") do |config|
  config.vm.box = "precise64"
  config.vm.network "forwarded_port", guest: 7474, host: 7474
  config.vm.network "forwarded_port", guest: 1984, host: 1984
  config.vm.synced_folder "workspace/basex/", "/opt/data/basex"
  config.vm.provision "ansible" do |ansible|
    ansible.playbook = "playbook.yml"
    ansible.sudo = true
  end
end
